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

package io.crate.analyze.relations;

import io.crate.exceptions.ColumnUnknownException;
import io.crate.expression.symbol.ScopedSymbol;
import io.crate.expression.symbol.Symbol;
import io.crate.expression.symbol.Symbols;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.RelationName;
import io.crate.metadata.table.Operation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <pre>{@code <relation> AS alias}</pre>
 *
 * This relation only provides a different name for an inner relation.
 * The {@link #outputs()} of the aliased-relation are {@link ScopedSymbol}s,
 * a 1:1 mapping of the outputs of the inner relation but associated with the aliased-relation.;
 */
public class AliasedAnalyzedRelation implements AnalyzedRelation, FieldResolver {

    private final AnalyzedRelation relation;
    private final RelationName alias;
    private final Map<ColumnIdent, ColumnIdent> aliasToColumnMapping;
    private final ArrayList<Symbol> outputs;

    public AliasedAnalyzedRelation(AnalyzedRelation relation, RelationName alias) {
        this(relation, alias, List.of());
    }

    AliasedAnalyzedRelation(AnalyzedRelation relation, RelationName alias, List<String> columnAliases) {
        this.relation = relation;
        this.alias = alias;
        aliasToColumnMapping = new HashMap<>(columnAliases.size());
        this.outputs = new ArrayList<>(relation.outputs().size());
        for (int i = 0; i < relation.outputs().size(); i++) {
            Symbol childOutput = relation.outputs().get(i);
            ColumnIdent childColumn = Symbols.pathFromSymbol(childOutput);
            if (i < columnAliases.size()) {
                ColumnIdent columnAlias = new ColumnIdent(columnAliases.get(i));
                aliasToColumnMapping.put(columnAlias, childColumn);
                outputs.add(new ScopedSymbol(this.alias, columnAlias, childOutput.valueType()));
            } else {
                aliasToColumnMapping.put(childColumn, childColumn);
                outputs.add(new ScopedSymbol(alias, childColumn, childOutput.valueType()));
            }
        }
    }

    @Override
    public Symbol getField(ColumnIdent column, Operation operation) throws UnsupportedOperationException, ColumnUnknownException {
        if (operation != Operation.READ) {
            throw new UnsupportedOperationException(operation + " is not supported on " + alias);
        }
        ColumnIdent childColumnName = aliasToColumnMapping.get(column);
        if (childColumnName == null) {
            return null;
        }
        Symbol field = relation.getField(childColumnName, operation);
        if (field == null) {
            return null;
        }
        ScopedSymbol scopedSymbol = new ScopedSymbol(alias, column, field.valueType());
        // If the scopedSymbol exists in `outputs`, return that instance so that IdentityHashMaps work
        int i = outputs.indexOf(scopedSymbol);
        if (i >= 0) {
            return outputs.get(i);
        }
        return scopedSymbol;
    }

    public AnalyzedRelation relation() {
        return relation;
    }

    @Override
    public RelationName relationName() {
        return alias;
    }

    @Nonnull
    @Override
    public List<Symbol> outputs() {
        return outputs;
    }

    @Override
    public String toString() {
        return relation + " AS " + alias;
    }

    @Override
    public <C, R> R accept(AnalyzedRelationVisitor<C, R> visitor, C context) {
        return visitor.visitAliasedAnalyzedRelation(this, context);
    }

    @Nullable
    @Override
    public Symbol resolveField(ScopedSymbol field) {
        if (!field.relation().equals(alias)) {
            throw new IllegalArgumentException(field + " does not belong to " + relationName());
        }
        ColumnIdent childColumnName = aliasToColumnMapping.get(field.column());
        var result = relation.getField(childColumnName, Operation.READ);
        if (result == null) {
            throw new IllegalArgumentException(field + " does not belong to " + relationName());
        }
        return result;
    }
}
