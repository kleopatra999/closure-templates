/*
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.parsepasses.contextautoesc;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SanitizedContentOperator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.AutoescapeMode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.LetContentNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyNode.RenderUnitNode;
import com.google.template.soy.soytree.TemplateBasicNode;
import com.google.template.soy.soytree.TemplateDelegateNode;
import com.google.template.soy.soytree.TemplateNode;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

/**
 * Inserts directives into print commands by looking at the context in which a print appears, and
 * derives templates and rewrites calls so that each template is entered only in contexts
 * consistent with its escaping conventions.
 *
 * E.g. it will {@link ContextualAutoescaper#rewrite rewrite}
 * <xmp class=prettyprint>
 * {template example autoescape="contextual"}
 *   <p>Hello, {$world}!</p>
 * {/template}
 * </xmp>
 * to
 * <xmp class=prettyprint>
 * {template example autoescape="contextual"}
 *   <p>Hello, {$world |escapeHtml}!</p>
 * {/template}
 * </xmp>
 *
 */
public final class ContextualAutoescaper {

  /**
   * Soy directives that cancel autoescaping (see
   * {@link SoyPrintDirective#shouldCancelAutoescape()}).
   */
  private final ImmutableSet<String> autoescapeCancellingDirectives;

  /** Maps print directive names to the content kinds they consume and produce. */
  private final Map<String, SanitizedContent.ContentKind> sanitizedContentOperators;

  /** For reporting errors. */
  private final ErrorReporter errorReporter;

  /** The conclusions drawn by the last {@link #rewrite}. */
  private Inferences inferences;

  /** Raw text nodes sliced by context. */
  private List<SlicedRawTextNode> slicedRawTextNodes;

  /**
   * This injected ctor provides a blank constructor that is filled, in normal compiler operation,
   * with the core and basic directives defined in com.google.template.soy.{basic,core}directives,
   * and any custom directives supplied on the command line.
   *
   * @param soyDirectivesMap Map of all SoyPrintDirectives (name to directive) such that
   *     {@code soyDirectivesMap.get(key).getName().equals(key)} for all key in
   *     {@code soyDirectivesMap.keySet()}.
   * @param errorReporter For reporting errors.
   */
  @Inject
  ContextualAutoescaper(
      final Map<String, SoyPrintDirective> soyDirectivesMap, ErrorReporter errorReporter) {
    // Compute the set of directives that are escaping directives.
    this(ImmutableSet.copyOf(Collections2.filter(
        soyDirectivesMap.keySet(),
        new Predicate<String>() {
          @Override
          public boolean apply(String directiveName) {
            return soyDirectivesMap.get(directiveName).shouldCancelAutoescape();
          }
        })),
        makeOperatorKindMap(soyDirectivesMap),
        errorReporter);
  }

  /**
   * @param autoescapeCancellingDirectives The Soy directives that cancel autoescaping (see
   *     {@link SoyPrintDirective#shouldCancelAutoescape()}).
   * @param sanitizedContentOperators Maps print directive names to the content kinds they
   *     consume and produce.
   */
  public ContextualAutoescaper(
      Iterable<String> autoescapeCancellingDirectives,
      Map<String, SanitizedContent.ContentKind> sanitizedContentOperators,
      ErrorReporter errorReporter) {
    this.autoescapeCancellingDirectives = ImmutableSet.copyOf(autoescapeCancellingDirectives);
    this.sanitizedContentOperators = ImmutableMap.copyOf(sanitizedContentOperators);
    this.errorReporter = errorReporter;
  }


  /**
   * Rewrites the given Soy files so that dynamic output is properly escaped according to the
   * context in which it appears.
   *
   * @param fileSet Modified in place.
   * @return Extra templates which were derived from templates under fileSet and which must be
   *     compiled with fileSet to produce a correct output.  See {@link DerivedTemplateUtils} for an
   *     explanation of these.
   * @throws SoyAutoescapeException If it is impossible to statically determine the context of
   *     portions of templates.
   *     It is not possible to decide what to do with {@code $x} in:
   *     <xmp class=prettyprint>
   *     <script>{if $condition}</script>{/if}
   *     alert('Is this script or text? {$x}');
   *     //<textarea><script></script><textarea></textarea>
   *     </xmp>
   */
  public List<TemplateNode> rewrite(SoyFileSetNode fileSet)
      throws SoyAutoescapeException {
    // Defensively copy so our loops below hold.
    List<SoyFileNode> files = ImmutableList.copyOf(fileSet.getChildren());

    Map<String, ImmutableList<TemplateNode>> templatesByName = findTemplates(files);

    // Inferences collects all the typing decisions we make, templates we derive, and escaping modes
    // we choose.
    Inferences inferences = new Inferences(
        autoescapeCancellingDirectives, fileSet.getNodeIdGenerator(), templatesByName);
    ImmutableList.Builder<SlicedRawTextNode> slicedRawTextNodesBuilder = ImmutableList.builder();

    Collection<TemplateNode> allTemplates = inferences.getAllTemplates();
    TemplateCallGraph callGraph = new TemplateCallGraph(templatesByName, errorReporter);
    // Generate a call graph, creating a dummy root that calls all non-private template in
    // Context.PCDATA, and then type the minimal ancestor set needed to reach all contextual
    // templates whether private or not.
    // This should have the effect of being a NOP when there are no contextual templates, will type
    // all contextual templates, and will not barf on private templates that might be declared
    // autoescape="false" because they do funky things that are provably safe by human reason but
    // not by this algorithm.
    Set<TemplateNode> templateNodesToType = callGraph.callersOf(
        Collections2.filter(allTemplates, IS_CONTEXTUAL));
    templateNodesToType.addAll(Collections2.filter(allTemplates, REQUIRES_INFERENCE));
    for (TemplateNode templateNode : templateNodesToType) {
      // In strict mode, the author specifies the kind of SanitizedContent to produce, and thus the
      // context in which to escape.
      Context startContext = (templateNode.getContentKind() != null) ?
          Context.getStartContextForContentKind(templateNode.getContentKind()) :
          Context.HTML_PCDATA;
      InferenceEngine.inferTemplateEndContext(
          templateNode,
          startContext,
          inferences,
          autoescapeCancellingDirectives,
          slicedRawTextNodesBuilder,
          errorReporter);
    }

    // Store inferences so that after processing, clients can access the output contexts for
    // templates.
    this.inferences = inferences;

    // Store context boundaries so that later passes can make use of element/attribute boundaries.
    this.slicedRawTextNodes = slicedRawTextNodesBuilder.build();

    new NonContextualTypedRenderUnitNodesVisitor().exec(fileSet);

    // Now that we know we don't fail with exceptions, apply the changes to the given files.
    return new Rewriter(inferences, sanitizedContentOperators, errorReporter).rewrite(fileSet);
  }


  /**
   * Null if no typing has been done for the named template, or otherwise the context after a call
   * to the named template.  Since we derive templates by start context at the call site, there
   * is no start context parameter.
   *
   * @param templateName A qualified template name.
   */
  public Context getTemplateEndContext(String templateName) {
    return inferences.getTemplateEndContext(templateName);
  }

  /**
   * Maps ranges of text-nodes to contexts so that later parse passes can add attributes or
   * elements.
   */
  public List<SlicedRawTextNode> getSlicedRawTextNodes() {
    return slicedRawTextNodes;
  }

  /**
   * Fills in the {@link Inferences} template name to node map.
   * @param files Modified in place.
   */
  private static Map<String, ImmutableList<TemplateNode>> findTemplates(
      Iterable<? extends SoyFileNode> files) {
    final Map<String, ImmutableList.Builder<TemplateNode>> templatesByName
        = Maps.newLinkedHashMap();
    for (SoyFileNode file : files) {
      for (TemplateNode template : file.getChildren()) {
        String templateName;
        if (template instanceof TemplateBasicNode) {
          templateName = template.getTemplateName();
        } else {
          templateName = ((TemplateDelegateNode) template).getDelTemplateName();
        }
        if (!templatesByName.containsKey(templateName)) {
          templatesByName.put(templateName, ImmutableList.<TemplateNode>builder());
        }
        templatesByName.get(templateName).add(template);
      }
    }
    final ImmutableMap.Builder<String, ImmutableList<TemplateNode>> templatesByNameBuilder
        = ImmutableMap.builder();
    for (Map.Entry<String, ImmutableList.Builder<TemplateNode>> e : templatesByName.entrySet()) {
      templatesByNameBuilder.put(e.getKey(), e.getValue().build());
    }
    return templatesByNameBuilder.build();
  }

  private static final Predicate<TemplateNode> IS_CONTEXTUAL = new Predicate<TemplateNode>() {
    @Override
    public boolean apply(TemplateNode templateNode) {
      return templateNode.getAutoescapeMode() == AutoescapeMode.CONTEXTUAL
          || templateNode.getAutoescapeMode() == AutoescapeMode.STRICT;
    }
  };

  private static final Predicate<TemplateNode> REQUIRES_INFERENCE
      = new Predicate<TemplateNode>() {
        @Override
        public boolean apply(TemplateNode templateNode) {
          // All strict and contextual. With strict, every template establishes its own context.
          // With contextual, even if we don't see any callers in the call graph, it still might be
          // called from another file.  This used to skip private templates, but private supposedly
          // only means the template can only be called by other templates, and even then, it is
          // not really enforced strongly by the Closure JS Compiler. (Prior to changing this,
          // there were a few templates that weren't contextually autoescaped because they were
          // private, but were still being called directly from JS.)
          return templateNode.getAutoescapeMode() == AutoescapeMode.STRICT ||
              templateNode.getAutoescapeMode() == AutoescapeMode.CONTEXTUAL;
        }
  };


  private static Map<String, SanitizedContent.ContentKind> makeOperatorKindMap(
      final Map<String, SoyPrintDirective> soyDirectivesMap) {
    ImmutableMap.Builder<String, SanitizedContent.ContentKind> operatorKindMapBuilder
        = ImmutableMap.builder();
    for (SoyPrintDirective directive : soyDirectivesMap.values()) {
      if (directive instanceof SanitizedContentOperator) {
        operatorKindMapBuilder.put(
            directive.getName(), ((SanitizedContentOperator) directive).getContentKind());
      }
    }
    return operatorKindMapBuilder.build();
  }

  private final class NonContextualTypedRenderUnitNodesVisitor
      extends AbstractSoyNodeVisitor<Void> {

    NonContextualTypedRenderUnitNodesVisitor() {
      super(ContextualAutoescaper.this.errorReporter);
    }

    @Override protected void visitTemplateNode(TemplateNode node) {
      if (node.getAutoescapeMode() == AutoescapeMode.NONCONTEXTUAL) {
        visitChildren(node);
      }
    }

    @Override protected void visitLetContentNode(LetContentNode node) {
      visitRenderUnitNode(node);
    }

    @Override protected void visitCallParamContentNode(CallParamContentNode node) {
      visitRenderUnitNode(node);
    }

    protected void visitRenderUnitNode(RenderUnitNode node) {
      if (node.getContentKind() != null) {
        // Not visiting children in this block.
        // In processing a strict block (any block with a kind), contextualAutoescaper will
        // automatically go into the children.
        // Secondly, CheckEscapingSanityVisitor makes sure that all the children {let} or {param}
        // blocks of a strict {let} or {param} block are also strict.
        ImmutableList.Builder<SlicedRawTextNode> slicedRawTextNodesBuilder =
            ImmutableList.builder();
        InferenceEngine.inferStrictRenderUnitNode(
            // As this visitor visits only non-contextual templates.
            AutoescapeMode.NONCONTEXTUAL,
            node,
            inferences,
            autoescapeCancellingDirectives,
            slicedRawTextNodesBuilder,
            errorReporter);
      } else {
        visitChildren(node);
      }
    }

    @Override protected void visitSoyNode(SoyNode node) {
      if (node instanceof ParentSoyNode<?>) {
        visitChildren((ParentSoyNode<?>) node);
      }
    }
  }
}
