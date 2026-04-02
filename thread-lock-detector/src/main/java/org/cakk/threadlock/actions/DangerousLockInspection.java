package org.cakk.threadlock.actions;

import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

public class DangerousLockInspection extends AbstractBaseJavaLocalInspectionTool {

  private static final String DESCRIPTION = "Dangerous or incorrect thread lock usage";

  @Override
  public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                 boolean isOnTheFly) {
    return new JavaElementVisitor() {

      @Override
      public void visitSynchronizedStatement(@NotNull PsiSynchronizedStatement stmt) {
        super.visitSynchronizedStatement(stmt);

        PsiExpression lockExpression = stmt.getLockExpression();
        if (lockExpression == null) return;

        String lockText = lockExpression.getText();

        // 1. Locking on 'this'
        if ("this".equals(lockText)) {
          holder.registerProblem(lockExpression,
                  "Synchronizing on 'this' is discouraged. Use a private final lock object instead.",
                  ProblemHighlightType.WARNING);
        }

        // 2. Locking on String literal
        else if (lockExpression instanceof PsiLiteralExpression literal &&
                literal.getType() != null &&
                "java.lang.String".equals(literal.getType().getCanonicalText())) {
          holder.registerProblem(lockExpression,
                  "Synchronizing on String literal can cause unintended contention across the JVM.",
                  ProblemHighlightType.WARNING);
        }

        // 3. Locking on boxed primitive or common objects
        else if (isBadLockTarget(lockExpression)) {
          holder.registerProblem(lockExpression,
                  "Synchronizing on " + lockText + " is dangerous or ineffective.",
                  ProblemHighlightType.WARNING);
        }
      }

      @Override
      public void visitMethod(@NotNull PsiMethod method) {
        super.visitMethod(method);

        if (method.hasModifierProperty(PsiModifier.SYNCHRONIZED)) {
          holder.registerProblem(method.getModifierList(),
                  "Synchronized methods can hold locks longer than necessary. " +
                          "Consider using synchronized blocks with a dedicated lock object.",
                  ProblemHighlightType.WEAK_WARNING);
        }
      }

      // Simple check for ReentrantLock without try-finally (basic version)
      @Override
      public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
        super.visitMethodCallExpression(expression);

        PsiReferenceExpression methodRef = expression.getMethodExpression();
        String methodName = methodRef.getReferenceName();

        if ("lock".equals(methodName)) {
          PsiMethod resolved = (PsiMethod) methodRef.resolve();
          if (resolved != null && isReentrantLock(resolved)) {
            // Very basic check - look for unlock in same method
            PsiMethod containingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod.class);
            if (containingMethod != null && !hasUnlockCall(containingMethod)) {
              holder.registerProblem(expression,
                      "ReentrantLock.lock() called without corresponding unlock() in try-finally.",
                      ProblemHighlightType.WARNING);
            }
          }
        }
      }
    };
  }

  private boolean isBadLockTarget(PsiExpression expr) {
    String text = expr.getText();
    return text.equals("getClass()") ||
            text.contains(".class") ||
            text.matches(".*(HashMap|ArrayList|HashSet|Integer|Long|Boolean).*");
  }

  private boolean isReentrantLock(PsiMethod method) {
    PsiClass clazz = method.getContainingClass();
    return clazz != null && "java.util.concurrent.locks.ReentrantLock".equals(clazz.getQualifiedName());
  }

  private boolean hasUnlockCall(PsiMethod method) {
    boolean[] hasUnlock = {false};
    method.accept(new JavaRecursiveElementVisitor() {
      @Override
      public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
        if ("unlock".equals(expression.getMethodExpression().getReferenceName())) {
          hasUnlock[0] = true;
        }
        super.visitMethodCallExpression(expression);
      }
    });
    return hasUnlock[0];
  }

  @Override
  public @NotNull String getDisplayName() {
    return "Dangerous thread lock usage";
  }

  @Override
  public @NotNull String getShortName() {
    return "DangerousLockUsage";
  }
}