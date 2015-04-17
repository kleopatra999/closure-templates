/*
 * Copyright 2015 Google Inc.
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

package com.google.template.soy.jbcsrc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.template.soy.jbcsrc.Statement.returnExpression;

import com.google.auto.value.AutoValue;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.jbcsrc.Expression.SimpleExpression;
import com.google.template.soy.jbcsrc.VariableSet.SaveRestoreState;
import com.google.template.soy.jbcsrc.api.AdvisingAppendable;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.TableSwitchGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * An object that manages generating the logic to save and restore execution state to enable 
 * rendering to pause partway through a template.
 *
 * <p>First, definitions:
 * <dl>
 *     <dt>Detach
 *     <dd>A 'detach' is the act of saving local state and returning control to our caller. 
 *         Logically, we are saving a continuation.
 *
 *     <dt>Detachable
 *     <dd>An operation that may conditionally detach.
 *
 *     <dt>Reattach
 *     <dd>A 'reattach' is the act of restoring state and jumping back to the location just before 
 *         the original 'detach'.  We are calling back into our saved 'continuation'.
 *
 *     <dt>Reattach Point
 *     <dd>The specific code location to which control should return.
 * </dl>
 *
 * <p>Each detachable method will look approximately like: <pre>{@code
 *
 *   int state;
 *   Result detachable() {
 *     switch (state) {
 *       case 0: goto L0;  // no locals for state 0
 *       case 1:
 *         // restore all variables active at state 1
 *         goto L1;
 *       case 2:
 *         // restore locals active at state 2
 *         goto L2;
 *       ...
 *       default:
 *         throw new AssertionError();
 *     }
 *     L0:
 *     // start of the method
 *     ...
 *   }}</pre>
 *
 * <p>Then prior to each detachable point we will assign a label and generate code that looks like
 * this: <pre>{@code
 *
 * LN:
 *   if (needs to detach) {
 *     save locals to fields
 *     state = N;
 *     return Result.detachCause(cause);
 *   }
 * }}</pre>
 *
 * <p>This object is mutable and depends on the state of the {@link VariableSet} to determine the
 * current set of active variables.  So it is important that uses of this object are sequenced
 * appropriately with operations that introduce (or remove) active variables.
 *
 * <p>Note, in the above examples, the caller is responsible for calculating when/why to detach
 * but this class is responsible for calculating the save/restore reattach logic.
 */
final class DetachState implements ExpressionDetacher.Factory {
  private final VariableSet variables;
  private final List<ReattachState> reattaches = new ArrayList<>();
  private final Expression thisExpr;
  private final FieldRef stateField;

  DetachState(VariableSet variables, Expression thisExpr, FieldRef stateField) {
    checkArgument(stateField.type().equals(Type.INT_TYPE));
    this.variables = variables;
    this.thisExpr = thisExpr;
    this.stateField = stateField;
    // Add a null at the head of the list so that the reattaches in the list match their state 
    // indices  e.g. reattach.get(2) is the reattach for state 2.  Because 0 is a special case for
    // 'initial call'.
    reattaches.add(null);
  }

  /**
   * A utility for generating detach blocks for expressions.
   */
  private static final class ExpressionDetacherImpl implements ExpressionDetacher {
    private final Label reattachPoint;
    private final Statement saveOperation;

    private ExpressionDetacherImpl(Label reattachPoint, Statement save) {
      this.reattachPoint = reattachPoint;
      this.saveOperation = save;
    }

    @Override public Expression makeDetachable(final Expression exp) {
      return new SimpleExpression(exp.resultType(), exp.isConstant()) {
        @Override void doGen(GeneratorAdapter adapter) {
          // Note, the reattach point is _before_ the expression.  This means that on reattaches
          // we rerun the entire expression rather than jumping to right before the detach point.
          // This is neccesary because we are not saving intermediate expression results which are
          // currently stored on the runtime stack.  saving the stack is believed to not be worth
          // the effort.
          adapter.mark(reattachPoint);
          exp.gen(adapter);
        }
      };
    }

    @Override public Expression resolveSoyValueProvider(final Expression soyValueProvider) {
      soyValueProvider.checkAssignableTo(Type.getType(SoyValueProvider.class));
      return new SimpleExpression(Type.getType(SoyValue.class), false) {
        @Override void doGen(GeneratorAdapter adapter) {
          // We use a bunch of dup() operations in order to save extra field reads and method
          // invocations.  This makes the expression api difficult/confusing to use.  So instead 
          // call a bunch of unchecked invocations.
          // Legend: SVP = SoyValueProvider, RS = ResolveStatus, Z = boolean, SV = SoyValue
          soyValueProvider.gen(adapter);                                  // Stack: SVP
          adapter.dup();                                                  // Stack: SVP, SVP
          MethodRef.SOY_VALUE_PROVIDER_STATUS.invokeUnchecked(adapter);   // Stack: SVP, RS
          adapter.dup();                                                  // Stack: SVP, RS, RS
          MethodRef.RENDER_RESULT_IS_DONE.invokeUnchecked(adapter);       // Stack: SVP, RS, Z
          // if isReady goto resolve
          Label resolve = new Label();
          adapter.ifZCmp(Opcodes.IFNE, resolve);                          // Stack: SVP, RS

          saveOperation.gen(adapter);
          adapter.returnValue();

          adapter.mark(resolve);
          adapter.pop();                                                  // Stack: SVP
          MethodRef.SOY_VALUE_PROVIDER_RESOLVE.invokeUnchecked(adapter);  // Stack: SV
        }
      };
    }
  }

  /**
   * Returns a {@link ExpressionDetacher} that can be used to instrument an expression with detach
   * reattach logic.
   */
  @Override public ExpressionDetacher createExpressionDetacher() {
    Label reattachPoint = new Label();
    int state = reattaches.size();
    SaveRestoreState saveRestoreState = variables.saveRestoreState();
    Statement restore = saveRestoreState.restore();
    reattaches.add(ReattachState.create(reattachPoint, restore));
    Statement saveState = stateField.putInstanceField(thisExpr, BytecodeUtils.constant(state));
    return new ExpressionDetacherImpl(
        reattachPoint, 
        Statement.concat(saveRestoreState.save(), saveState));
  }

  /**
   * Returns a Statement that will conditionally detach if the given {@link AdvisingAppendable} has
   * been {@link AdvisingAppendable#softLimitReached() output limited}.
   */
  Statement detachLimited(Expression appendable) {
    checkArgument(appendable.resultType().equals(Type.getType(AdvisingAppendable.class)));
    final Label reattachPoint = new Label();
    int state = reattaches.size();
    final SaveRestoreState saveRestoreState = variables.saveRestoreState();
    
    Statement restore = saveRestoreState.restore();
    reattaches.add(ReattachState.create(reattachPoint, restore));
    final Expression isSoftLimited = MethodRef.ADVISING_APPENDABLE_SOFT_LIMITED.invoke(appendable);
    final Statement returnLimited = returnExpression(MethodRef.RENDER_RESULT_LIMITED.invoke());
    final Statement saveState = 
        stateField.putInstanceField(thisExpr, BytecodeUtils.constant(state));
    return new Statement() {
      @Override void doGen(GeneratorAdapter adapter) {
        isSoftLimited.gen(adapter);
        adapter.ifZCmp(Opcodes.IFEQ, reattachPoint);  // if !softLimited
        // ok we were limited, save state and return
        saveRestoreState.save().gen(adapter);  // save locals
        saveState.gen(adapter);  // save the state field
        returnLimited.gen(adapter);
        // Note, the reattach point for 'limited' is _after_ the check.  That means we do not 
        // recheck the limit state.  So if a caller calls us back without freeing any buffer we
        // will print more before checking again.  This is fine, because our caller is breaking the
        // contract.
        adapter.mark(reattachPoint);
      }
    };
  }

  /**
   * Returns a statement that generates the reattach jump table.
   * 
   * <p>Note: This statement should be the <em>first</em> statement in any detachable method.
   */
  Statement generateReattachTable() {
    final Expression readField = stateField.accessor(thisExpr);
    final Statement defaultCase = 
        Statement.throwExpression(MethodRef.RUNTIME_UNEXPECTED_STATE_ERROR.invoke(readField));
    return new Statement() {
      @Override void doGen(final GeneratorAdapter adapter) {
        int[] keys = new int[reattaches.size()];
        for (int i = 0; i < keys.length; i++) {
          keys[i] = i;
        }
        readField.gen(adapter);
        // Generate a switch table.  Note, while it might be preferable to just 'goto state', Java
        // doesn't allow computable gotos (probably because it makes verification impossible).  So
        // instead we emulate that with a jump table.  And anyway we still need to execute 'restore'
        // logic to repopulate the local variable tables, so the 'case' statements are a natural
        // place for that logic to live.
        adapter.tableSwitch(keys, new TableSwitchGenerator() {
          @Override public void generateCase(int key, Label end) {
            if (key == 0) {
              // State 0 is special, it means initial state, so we just jump to the very end
              adapter.goTo(end);
              return;
            }
            ReattachState reattachState = reattaches.get(key);
            // restore and jump!
            reattachState.restoreStatement().gen(adapter);
            adapter.goTo(reattachState.reattachPoint());
          }

          @Override public void generateDefault() {
            defaultCase.gen(adapter);
          }
        },
        // Use tableswitch instead of lookupswitch.  TableSwitch is appropriate because our case
        // labels are sequential integers in the range [0, N).  This means that switch is O(1) and
        // there are no 'holes' meaning that it is compact in the bytecode.
        true);
      }
    };
  }

  @AutoValue abstract static class ReattachState {
    static ReattachState create(Label reattachPoint, Statement restore) {
      return new AutoValue_DetachState_ReattachState(reattachPoint, restore);
    }

    /** The label where control should resume when continuing. */
    abstract Label reattachPoint();
    
    /** The statement that restores the state of local variables so we can resume execution. */
    abstract Statement restoreStatement();
  }
}
