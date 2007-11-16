/*
 * Copyright 2000-2007 JetBrains s.r.o.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.plugins.groovy.lang.psi.impl.statements;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.pom.java.PomField;
import com.intellij.psi.*;
import com.intellij.psi.meta.PsiMetaData;
import com.intellij.psi.meta.PsiMetaOwner;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.search.SearchScope;
import com.intellij.util.ArrayUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.grails.lang.gsp.psi.groovy.api.GrGspDeclarationHolder;
import org.jetbrains.plugins.groovy.lang.psi.GroovyElementVisitor;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.GrModifierList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrField;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinitionBody;
import org.jetbrains.plugins.groovy.lang.psi.impl.PsiImplUtil;
import org.jetbrains.plugins.groovy.lang.psi.impl.synthetic.AccessorMethod;

/**
 * User: Dmitry.Krasilschikov
 * Date: 25.05.2007
 */
public class GrFieldImpl extends GrVariableImpl implements GrField {
  private AccessorMethod mySetter;
  private AccessorMethod myGetter;
  private boolean myGetterInitialized = false;
  private boolean mySetterInitialized = false;

  public GrFieldImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(GroovyElementVisitor visitor) {
    visitor.visitField(this);
  }

  public String toString() {
    return "Field";
  }

  public void setInitializer(@Nullable PsiExpression psiExpression) throws IncorrectOperationException {
  }

  public PomField getPom() {
    return null;
  }

  public boolean isDeprecated() {
    return false;
  }

  public PsiClass getContainingClass() {
    PsiElement parent = getParent().getParent();
    if (parent instanceof GrTypeDefinitionBody) {
      final PsiElement pparent = parent.getParent();
      if (pparent instanceof PsiClass) {
        return (PsiClass) pparent;
      }
    }
    
    if (parent instanceof GrGspDeclarationHolder) {
      return ((GrGspDeclarationHolder) parent).getContainingClass();
    }

    return null;
  }

  public boolean isProperty() {
    final GrModifierList modifierList = getModifierList();
    return modifierList != null && !modifierList.hasExplicitVisibilityModifiers();
  }

  public PsiMethod getSetter() {
    mySetterInitialized = true;
    if (!isProperty()) return null;
    final AccessorMethod setter = new AccessorMethod(this, true);
    final PsiClass clazz = getContainingClass();
    if (clazz == null || clazz.findMethodBySignature(setter, false) == null) {
      mySetter = setter;
    } else {
      mySetter = null;
    }

    return mySetter;
  }

  public void clearCaches() {
    myGetter = null;
    mySetter = null;
    myGetterInitialized = false;
    mySetterInitialized = false;
  }

  public PsiMethod getGetter() {
    myGetterInitialized = true;
    if (!isProperty()) return null;
    final AccessorMethod getter = new AccessorMethod(this, false);
    final PsiClass clazz = getContainingClass();
    if (clazz == null || clazz.findMethodBySignature(getter, false) == null) {
      myGetter = getter;
    } else {
      myGetter = null;
    }

    return myGetter;
  }

  @NotNull
  public SearchScope getUseScope() {
    return PsiImplUtil.getUseScope(this);
  }

  public PsiElement getOriginalElement() {
    final PsiClass containingClass = getContainingClass();
    if (containingClass == null) return this;
    PsiClass originalClass = (PsiClass) containingClass.getOriginalElement();
    PsiField originalField = originalClass.findFieldByName(getName(), false);
    return originalField != null ? originalField : this;
  }
}
