/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
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
package com.intellij.psi.impl.java.stubs;

import com.intellij.psi.PsiFunctionalExpression;
import com.intellij.psi.stubs.IStubElementType;
import com.intellij.psi.stubs.StubBase;
import com.intellij.psi.stubs.StubElement;
import com.intellij.util.io.StringRef;

public class FunctionalExpressionStub<T extends PsiFunctionalExpression> extends StubBase<T> {
  private final StringRef myPresentableTextRef;

  protected FunctionalExpressionStub(StubElement parent, IStubElementType elementType, StringRef presentableTextRef) {
    super(parent, elementType);
    myPresentableTextRef = presentableTextRef;
  }

  public String getPresentableText() {
    return myPresentableTextRef.getString();
  }
}