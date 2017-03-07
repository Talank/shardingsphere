/*
 * Copyright 1999-2101 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.druid.sql.parser;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.context.CommonSelectItemContext;
import com.alibaba.druid.sql.context.GroupByContext;
import com.alibaba.druid.sql.context.SelectSQLContext;
import com.alibaba.druid.sql.context.TableContext;
import com.alibaba.druid.sql.context.TableToken;
import com.alibaba.druid.sql.lexer.Token;
import com.dangdang.ddframe.rdb.sharding.parser.result.merger.OrderByColumn;
import com.dangdang.ddframe.rdb.sharding.parser.result.router.ConditionContext;
import com.dangdang.ddframe.rdb.sharding.util.SQLUtil;
import com.google.common.base.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter(AccessLevel.PROTECTED)
public abstract class AbstractSelectParser {
    
    private SQLExprParser exprParser;
    
    private final SelectSQLContext sqlContext;
    
    @Setter
    private int parametersIndex;
    
    public AbstractSelectParser(final SQLExprParser exprParser) {
        this.exprParser = exprParser;
        sqlContext = new SelectSQLContext(getExprParser().getLexer().getInput());
    }
    
    /**
     * 解析查询.
     * 
     * @return 解析结果
     */
    public final SelectSQLContext parse() {
        query();
        sqlContext.getOrderByContexts().addAll(exprParser.parseOrderBy());
        customizedSelect(sqlContext);
        return sqlContext;
    }
    
    protected void customizedSelect(final SelectSQLContext sqlContext) {
    }
    
    protected SQLSelectQuery query() {
        if (getExprParser().getLexer().equalToken(Token.LEFT_PAREN)) {
            getExprParser().getLexer().nextToken();
            SQLSelectQuery select = query();
            getExprParser().getLexer().accept(Token.RIGHT_PAREN);
            queryRest();
            return select;
        }
        SQLSelectQueryBlock queryBlock = new SQLSelectQueryBlock();
        getExprParser().getLexer().accept(Token.SELECT);
        getExprParser().getLexer().skipIfEqual(Token.COMMENT);
        parseDistinct();
        parseSelectList();
        parseFrom();
        parseWhere();
        parseGroupBy();
        queryRest();
        return queryBlock;
    }
    
    protected final void parseDistinct() {
        if (getExprParser().getLexer().equalToken(Token.DISTINCT, Token.DISTINCTROW, Token.UNION)) {
            sqlContext.setDistinct(true);
            getExprParser().getLexer().nextToken();
            if (hasDistinctOn() && getExprParser().getLexer().equalToken(Token.ON)) {
                getExprParser().getLexer().nextToken();
                getExprParser().getLexer().skipParentheses();
            }
        } else if (getExprParser().getLexer().equalToken(Token.ALL)) {
            getExprParser().getLexer().nextToken();
        }
    }
    
    protected boolean hasDistinctOn() {
        return false;
    }
    
    protected final void parseSelectList() {
        int index = 1;
        do {
            SQLSelectItem selectItem = exprParser.parseSelectItem(index, sqlContext);
            index++;
            sqlContext.getItemContexts().add(selectItem.getSelectItemContext());
            if (selectItem.getSelectItemContext() instanceof CommonSelectItemContext) {
                if (((CommonSelectItemContext) selectItem.getSelectItemContext()).isStar()) {
                    sqlContext.setContainStar(true);
                }
            }
        } while (getExprParser().getLexer().skipIfEqual(Token.COMMA));
        sqlContext.setSelectListLastPosition(getExprParser().getLexer().getCurrentPosition() - getExprParser().getLexer().getLiterals().length());
    }
    
    protected void queryRest() {
        if (getExprParser().getLexer().equalToken(Token.UNION, Token.EXCEPT, Token.INTERSECT, Token.MINUS)) {
            throw new ParserUnsupportedException(getExprParser().getLexer().getToken());
        }
    }
    
    protected final void parseWhere() {
        if (sqlContext.getTables().isEmpty()) {
            return;
        }
        Optional<ConditionContext> conditionContext = exprParser.parseWhere(sqlContext);
        if (conditionContext.isPresent()) {
            sqlContext.getConditionContexts().add(conditionContext.get());
        }
        parametersIndex = exprParser.getParametersIndex();
    }
    
    protected void parseGroupBy() {
        if (getExprParser().getLexer().skipIfEqual(Token.GROUP)) {
            getExprParser().getLexer().accept(Token.BY);
            while (true) {
                addGroupByItem(exprParser.expr());
                if (!getExprParser().getLexer().equalToken(Token.COMMA)) {
                    break;
                }
                getExprParser().getLexer().nextToken();
            }
            while (getExprParser().getLexer().equalToken(Token.WITH) || getExprParser().getLexer().getLiterals().equalsIgnoreCase("ROLLUP")) {
                getExprParser().getLexer().nextToken();
            }
            if (getExprParser().getLexer().skipIfEqual(Token.HAVING)) {
                exprParser.expr();
            }
        } else if (getExprParser().getLexer().skipIfEqual(Token.HAVING)) {
            exprParser.expr();
        }
    }
    
    protected final void addGroupByItem(final SQLExpr sqlExpr) {
        OrderByColumn.OrderByType orderByType = OrderByColumn.OrderByType.ASC;
        if (getExprParser().getLexer().equalToken(Token.ASC)) {
            getExprParser().getLexer().nextToken();
        } else if (getExprParser().getLexer().skipIfEqual(Token.DESC)) {
            orderByType = OrderByColumn.OrderByType.DESC;
        }
        if (sqlExpr instanceof SQLPropertyExpr) {
            SQLPropertyExpr expr = (SQLPropertyExpr) sqlExpr;
            sqlContext.getGroupByContexts().add(new GroupByContext(Optional.of(SQLUtil.getExactlyValue(expr.getOwner().toString())), SQLUtil.getExactlyValue(expr.getSimpleName()), orderByType));
        } else if (sqlExpr instanceof SQLIdentifierExpr) {
            SQLIdentifierExpr expr = (SQLIdentifierExpr) sqlExpr;
            sqlContext.getGroupByContexts().add(new GroupByContext(Optional.<String>absent(), SQLUtil.getExactlyValue(expr.getSimpleName()), orderByType));
        }
    }
    
    public final void parseFrom() {
        if (getExprParser().getLexer().skipIfEqual(Token.FROM)) {
            parseTableSource();
        }
    }
    
    public List<TableContext> parseTableSource() {
        if (getExprParser().getLexer().equalToken(Token.LEFT_PAREN)) {
            throw new UnsupportedOperationException("Cannot support subquery");
        }
        parseTableFactor();
        parseJoinTable();
        return sqlContext.getTables();
    }
    
    protected final void parseTableFactor() {
        int beginPosition = getExprParser().getLexer().getCurrentPosition() - getExprParser().getLexer().getLiterals().length();
        String literals = getExprParser().getLexer().getLiterals();
        getExprParser().getLexer().nextToken();
        if (getExprParser().getLexer().skipIfEqual(Token.DOT)) {
            getExprParser().getLexer().nextToken();
            getExprParser().as();
            return;
        }
        // FIXME 根据shardingRule过滤table
        sqlContext.getSqlTokens().add(new TableToken(beginPosition, literals, SQLUtil.getExactlyValue(literals)));
        sqlContext.getTables().add(new TableContext(literals, SQLUtil.getExactlyValue(literals), getExprParser().as()));
    }
    
    protected void parseJoinTable() {
        if (getExprParser().isJoin()) {
            parseTableSource();
            if (getExprParser().getLexer().skipIfEqual(Token.ON)) {
                int leftStartPosition = getExprParser().getLexer().getCurrentPosition();
                SQLExpr sqlExpr = exprParser.expr();
                if (sqlExpr instanceof SQLBinaryOpExpr) {
                    SQLBinaryOpExpr binaryOpExpr = (SQLBinaryOpExpr) sqlExpr;
                    if (binaryOpExpr.getLeft() instanceof SQLPropertyExpr) {
                        SQLPropertyExpr sqlPropertyExpr = (SQLPropertyExpr) binaryOpExpr.getLeft();
                        for (TableContext each : sqlContext.getTables()) {
                            if (each.getName().equalsIgnoreCase(SQLUtil.getExactlyValue(sqlPropertyExpr.getOwner().toString()))) {
                                sqlContext.getSqlTokens().add(new TableToken(leftStartPosition, sqlPropertyExpr.getOwner().toString(), SQLUtil.getExactlyValue(sqlPropertyExpr.getOwner().toString())));
                            }
                        }
                    }
                    if (binaryOpExpr.getRight() instanceof SQLPropertyExpr) {
                        SQLPropertyExpr sqlPropertyExpr = (SQLPropertyExpr) binaryOpExpr.getRight();
                        for (TableContext each : sqlContext.getTables()) {
                            if (each.getName().equalsIgnoreCase(SQLUtil.getExactlyValue(sqlPropertyExpr.getOwner().toString()))) {
                                sqlContext.getSqlTokens().add(
                                        new TableToken(binaryOpExpr.getRightStartPosition(), sqlPropertyExpr.getOwner().toString(), SQLUtil.getExactlyValue(sqlPropertyExpr.getOwner().toString())));
                            }
                        }
                    }
                }
            } else if (getExprParser().getLexer().skipIfEqual(Token.USING)) {
                getExprParser().getLexer().skipParentheses();
            }
            parseJoinTable();
        }
    }
}
