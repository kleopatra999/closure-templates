/*
 * Copyright 2008 Google Inc.
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

package com.google.template.soy.parsepasses;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.coredirectives.EscapeHtmlDirective;
import com.google.template.soy.coredirectives.NoAutoescapeDirective;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyError;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.AutoescapeMode;
import com.google.template.soy.soytree.PrintDirectiveNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.TemplateNode;

import java.util.Map;

import javax.inject.Inject;

/**
 * Visitor for performing deprecated-noncontextual autoescape.
 *
 * <p>Note: This does nothing for strict autoescaping, which is the new standard; this implements
 * the older non-contextual autoescaping that incorrectly treats everything as HTML. For backwards
 * compatibility, Soy continues to support that mode.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 * <p>{@link #exec} should be called on a full parse tree. The directives on 'print' nodes may be
 * modified. There is no return value.
 *
 */
public final class PerformDeprecatedNoncontextualAutoescapeVisitor extends
    AbstractSoyNodeVisitor<Void> {

  private static final SoyError UNKNOWN_PRINT_DIRECTIVE = SoyError.of(
        "Unknown print directive ''{0}''.");

  /** Map of all SoyPrintDirectives (name to directive). */
  private final Map<String, SoyPrintDirective> soyDirectivesMap;

  /** The node id generator for the parse tree. Retrieved from the root SoyFileSetNode. */
  private IdGenerator nodeIdGen;

  /** The autoescape mode of the current template. */
  private AutoescapeMode autoescapeMode;


  /**
   * @param soyDirectivesMap Map of all SoyPrintDirectives (name to directive).
   */
  @Inject
  public PerformDeprecatedNoncontextualAutoescapeVisitor(
      Map<String, SoyPrintDirective> soyDirectivesMap, ErrorReporter errorReporter) {
    super(errorReporter);
    this.soyDirectivesMap = soyDirectivesMap;
  }


  // -----------------------------------------------------------------------------------------------
  // Implementations for specific nodes.


  @Override protected void visitSoyFileSetNode(SoyFileSetNode node) {
    nodeIdGen = node.getNodeIdGenerator();
    visitChildren(node);
  }


  @Override protected void visitTemplateNode(TemplateNode node) {
    autoescapeMode = node.getAutoescapeMode();
    visitChildren(node);
    autoescapeMode = null;
  }


  @Override protected void visitPrintNode(PrintNode node) {

    if (autoescapeMode == AutoescapeMode.NONCONTEXTUAL ||
        autoescapeMode == AutoescapeMode.NOAUTOESCAPE) {
      // Traverse the list to (a) record whether we saw any directive that cancels autoescape
      // (including 'noAutoescape' of course) and (b) remove 'noAutoescape' directives.
      boolean shouldCancelAutoescape = false;
      for (PrintDirectiveNode directiveNode : ImmutableList.copyOf(node.getChildren())) {
        SoyPrintDirective directive = soyDirectivesMap.get(directiveNode.getName());
        if (directive == null) {
          errorReporter.report(
              directiveNode.getSourceLocation(), UNKNOWN_PRINT_DIRECTIVE, directiveNode.getName());
        } else if (directive.shouldCancelAutoescape()) {
          shouldCancelAutoescape = true;
          if (autoescapeMode == AutoescapeMode.NOAUTOESCAPE &&
              directive instanceof NoAutoescapeDirective) {
            // Remove reundant noAutoescape in autoescape="deprecated-noautoescape" templates;
            // however, keep it for other templates.  This ensures filterNoAutoescape gets called
            // for all (even non-contextually) autoescaped templates, as a safeguard against
            // tainted ContentKind.TEXT from ending up noAutoescaped.  BUT, deprecated-noautoescape
            // templates make no guarantees and are often used in the same way kind="text" is.
            node.removeChild(directiveNode);
          }
        }
      }

      // If appropriate, apply autoescape by adding an |escapeHtml directive (should be applied
      // first because other directives may add HTML tags).
      // TODO(gboyer): This behavior is actually very confusing, because for escaping to be safe,
      // it needs to be applied last.  The logic in Rewriter.java is superior because it inserts
      // the escaping before any SanitizedContentOperators but after other directives.  In any
      // case, the motivation for fixing this is low because it would introduce risk, and nobody
      // should use deprecated-noncontextual autoescape.
      if (autoescapeMode == AutoescapeMode.NONCONTEXTUAL && !shouldCancelAutoescape) {
        PrintDirectiveNode newEscapeHtmlDirectiveNode = new PrintDirectiveNode.Builder(
            nodeIdGen.genId(), EscapeHtmlDirective.NAME, "", SourceLocation.UNKNOWN)
            .build(errorReporter);
        node.addChild(0, newEscapeHtmlDirectiveNode);
      }
    } else {
      // Sanity check: Make sure that this pass is never used without first running the contextual
      // autoescaper.
      Preconditions.checkState(
          !node.getChildren().isEmpty(),
          "Internal error: A contextual or strict template has a print node that was never "
              + "assigned any escape directives: %s at %s",
          node.toSourceString(),
          node.getSourceLocation());
    }
  }


  // -----------------------------------------------------------------------------------------------
  // Fallback implementation.


  @Override protected void visitSoyNode(SoyNode node) {
    if (node instanceof ParentSoyNode<?>) {
      visitChildren((ParentSoyNode<?>) node);
    }
  }

}
