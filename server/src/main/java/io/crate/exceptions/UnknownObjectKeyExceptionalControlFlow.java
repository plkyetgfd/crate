/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.exceptions;

import java.util.Locale;

/**
 * This is not a traditional exception, but is an exception as control flow that is used during subscript expression analyses.
 * Thrown from Tableinfo.getDynamics and caught by FullQualifiedNameFieldProvider.getField for instant stack unwinding.
 */
public class UnknownObjectKeyExceptionalControlFlow extends RuntimeException implements CrateException {

    public UnknownObjectKeyExceptionalControlFlow(String columnName) {
        super(String.format(Locale.ENGLISH, "Column %s unknown", columnName));
    }

    @Override
    public <C, R> R accept(CrateExceptionVisitor<C, R> exceptionVisitor, C context) {
        return exceptionVisitor.visitCrateException(this, context);
    }
}
