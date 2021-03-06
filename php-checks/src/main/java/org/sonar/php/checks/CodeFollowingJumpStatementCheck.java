/*
 * SonarQube PHP Plugin
 * Copyright (C) 2010-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.php.checks;

import java.util.List;
import org.sonar.check.Rule;
import org.sonar.php.checks.utils.AbstractStatementsCheck;
import org.sonar.php.tree.impl.PHPTree;
import org.sonar.plugins.php.api.tree.Tree;
import org.sonar.plugins.php.api.tree.Tree.Kind;
import org.sonar.plugins.php.api.tree.statement.StatementTree;

@Rule(key = CodeFollowingJumpStatementCheck.KEY)
public class CodeFollowingJumpStatementCheck extends AbstractStatementsCheck {

  public static final String KEY = "S1763";
  private static final String MESSAGE = "Remove the code after this \"%s\".";

  private static final Kind[] JUMP_KINDS = {
    Kind.BREAK_STATEMENT,
    Kind.RETURN_STATEMENT,
    Kind.CONTINUE_STATEMENT,
    Kind.THROW_STATEMENT
  };

  private static final Kind[] NO_ACTION_KINDS = {
    Kind.EMPTY_STATEMENT,
    Kind.CLASS_DECLARATION,
    Kind.FUNCTION_DECLARATION,
    Kind.INTERFACE_DECLARATION,
    Kind.TRAIT_DECLARATION,
    Kind.NAMESPACE_STATEMENT,
    Kind.USE_STATEMENT,
    Kind.CONSTANT_DECLARATION,
    Kind.INLINE_HTML
  };

  @Override
  public void visitNode(Tree tree) {
    List<StatementTree> statements = getStatements(tree);

    for (int i = 0; i < statements.size() - 1; i++) {
      StatementTree currentStatement = statements.get(i);

      if (currentStatement.is(JUMP_KINDS) && hasActionStatement(statements.subList(i + 1, statements.size()), tree)) {
        String message = String.format(MESSAGE, ((PHPTree) currentStatement).getFirstToken().text());
        context().newIssue(this, ((PHPTree) currentStatement).getFirstToken(), message);
      }
    }

  }

  private static boolean hasActionStatement(List<StatementTree> statements, Tree parent) {
    if (parent.is(Kind.CASE_CLAUSE, Kind.DEFAULT_CLAUSE) && statements.get(0).is(Kind.BREAK_STATEMENT)) {
      return false;
    }
    for (StatementTree statement : statements) {
      if (statement.is(Kind.LABEL)) {
        return false;
      } else if (!statement.is(NO_ACTION_KINDS)) {
        return true;
      }
    }
    return false;
  }

}
