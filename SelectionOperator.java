import net.sf.jsqlparser.eval.Eval;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.arithmetic.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SubSelect;

import java.sql.SQLException;
import java.util.*;

/**
 * Created by Karan on 6/26/2017.
 */
public class SelectionOperator implements Operator, ExpressionVisitor {

    HashMap<String, HashMap<String, ColumnIdType>> databaseMap = new HashMap<>();
    HashMap<String, Operator> operatorMap;
    Column[] schema;
    Expression condition;
    PrimitiveValue[] tuple;
    PrimitiveValue[] finalTuple;
    HashMap<String, String> aliasHashMap;
    ArrayList<PrimitiveValue[]> smallJoin;
    ArrayList<PrimitiveValue[]> bigJoin;
    Column[] currentSchema;
    ArrayList<String> joinedTablesList;
    HashMap<String, CreateTable> createTableMap;
    HashMap<String, Integer> currentSchemaIndex;
    Boolean leftTupleNull;

    public SelectionOperator(HashMap<String, HashMap<String, ColumnIdType>> databaseMap,
                             HashMap<String, Operator> operatorMap, Column[] schema,
                             Expression condition, HashMap<String, String> aliasHashMap,
                             HashMap<String, CreateTable> createTableMap) {
        this.databaseMap = databaseMap;
        this.operatorMap = operatorMap;
        this.schema = schema;
        this.condition = condition;
        this.aliasHashMap = aliasHashMap;
        this.bigJoin = new ArrayList<>();
        this.createTableMap = createTableMap;
    }
    public PrimitiveValue[] readOneTuple() {
        tuple = null;
        leftTupleNull = false;
        joinedTablesList = new ArrayList<>();
        smallJoin = new ArrayList<>();
        if(operatorMap.keySet().size() == 1) {
            Operator input = null;
            for (String key : operatorMap.keySet()) {
                input = operatorMap.get(key);
            }
            do {
                tuple = input.readOneTuple();
                if(tuple == null) {
                    return null;
                }

                final PrimitiveValue[] tupleCopy = tuple;
                Evaluator eval = new Evaluator(tupleCopy,schema,aliasHashMap);
                try {
                    PrimitiveValue result = eval.eval(condition);
                    BooleanValue boolResult = (BooleanValue)result;
                    if(!boolResult.getValue()) {
                        tuple = null;
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }

            } while (tuple == null);
            return tuple;
        } else {
            while (bigJoin.isEmpty()) {
                ArrayList<Expression> expressionArrayList = generateOrderedExpressionList(condition);
                for(Expression exp : expressionArrayList) {
                    exp.accept(this);
                }
//                condition.accept(this);
                if (bigJoin.isEmpty() && leftTupleNull) {
                    return null;
                }
                joinedTablesList = new ArrayList<>();
            }
            tuple = bigJoin.get(0);
            bigJoin.remove(0);

            assert tuple.length==schema.length : "Tuple length does not match schema length";
            currentSchemaIndex = new HashMap<>();
            for(int i = 0; i < currentSchema.length; i++) {
                currentSchemaIndex.put(currentSchema[i].getWholeColumnName().toLowerCase(), i);
            }

            finalTuple = new PrimitiveValue[tuple.length];
            for(int i = 0; i < tuple.length; i++) {
                Column col = schema[i];
                int index = currentSchemaIndex.get(col.getWholeColumnName().toLowerCase());
                finalTuple[i] = tuple[index];
            }
            return finalTuple;

        }
    }

    ArrayList<Expression> generateExpressionList(Expression e) {
        ArrayList<Expression> expressionArrayList = new ArrayList<>();
        if(e instanceof AndExpression) {
            expressionArrayList = generateExpressionList(((AndExpression) e).getLeftExpression());
            expressionArrayList.addAll(generateExpressionList(((AndExpression) e).getRightExpression()));
            return expressionArrayList;
        } else {
            expressionArrayList.add(e);
            return expressionArrayList;
        }
    }

    ArrayList<Expression> generateOrderedExpressionList(Expression e) {
        ArrayList<Expression> expressionArrayList = generateExpressionList(e);
        ArrayList<Expression> expressionArrayListCopy = new ArrayList<>(expressionArrayList);
        ArrayList<Expression> sortedExpList = new ArrayList<>();
        HashSet<String> tableHashSet = new HashSet<>();
        int joinCount = 0;
        for (Expression exp : expressionArrayList) {
            if(exp instanceof EqualsTo) {
                if(((EqualsTo) exp).getLeftExpression() instanceof Column
                        && ((EqualsTo) exp).getRightExpression() instanceof Column) {
                    joinCount++;
                }
            }
        }

        while (!expressionArrayList.isEmpty()) {
            if(joinCount != 0) {
                Iterator<Expression> iterator = expressionArrayList.iterator();
                while (iterator.hasNext()){
                    Expression exp = iterator.next();
                    if(exp instanceof EqualsTo) {
                        Expression leftExpr = ((EqualsTo) exp).getLeftExpression();
                        Expression rightExpr = ((EqualsTo) exp).getRightExpression();
                        if (sortedExpList.isEmpty()) {
                            if (leftExpr instanceof Column && rightExpr instanceof Column) {
                                sortedExpList.add(exp);
                                tableHashSet.add(((Column) leftExpr).getTable().getWholeTableName().toLowerCase());
                                tableHashSet.add(((Column) rightExpr).getTable().getWholeTableName().toLowerCase());
                                joinCount--;
                                iterator.remove();
                            }
                        } else {
                            if (leftExpr instanceof Column && rightExpr instanceof Column) {
                                String leftTable = ((Column) leftExpr).getTable().getWholeTableName().toLowerCase();
                                String rightTable = ((Column) rightExpr).getTable().getWholeTableName().toLowerCase();
                                if(tableHashSet.contains(leftTable)
                                        || tableHashSet.contains(rightTable)) {
                                    sortedExpList.add(exp);
                                    tableHashSet.add(((Column) leftExpr).getTable().getWholeTableName().toLowerCase());
                                    tableHashSet.add(((Column) rightExpr).getTable().getWholeTableName().toLowerCase());
                                    joinCount--;
                                    iterator.remove();
                                }
                            }
                        }
                    }
                }
            } else {
                Iterator<Expression> iterator = expressionArrayList.iterator();
                while (iterator.hasNext()){
                    Expression exp = iterator.next();
                    sortedExpList.add(exp);
                    iterator.remove();
                }
            }

        }
        return sortedExpList;
    }

    public void reset()
    {
        for (String key : operatorMap.keySet()) {
            Operator input = operatorMap.get(key);
            input.reset();
        }
    }

    public void visit(NullValue nullValue) {
        System.out.println("InsideNullValueExpression");
    }
    public void visit(Function function) {
        System.out.println("InsideFunctionExpression");
    }
    public void visit(InverseExpression inverseExpression) {
        System.out.println("InsideInverseExpression");
    }
    public void visit(JdbcParameter jdbcParameter) {
        System.out.println("InsideJdbcParameterExpression");
    }
    public void visit(DoubleValue doubleValue) {
        System.out.println("InsideDoubleValueExpression");
    }
    public void visit(LongValue longValue) {
        System.out.println("InsideLongValueExpression");
    }
    public void visit(DateValue dateValue) {
        System.out.println("InsideDateValueExpression");
    }
    public void visit(TimeValue timeValue) {
        System.out.println("InsideTimeValueExpression");
    }
    public void visit(TimestampValue timestampValue) {
        System.out.println("InsideTimeStampValueExpression");
    }
    public void visit(BooleanValue booleanValue) {
        System.out.println("InsideBooleanValueExpression");
    }
    public void visit(StringValue stringValue) {
        System.out.println("InsideStringValueExpression");
    }
    public void visit(Addition addition) {
        System.out.println("InsideAdditionExpression");
    }
    public void visit(Division division) {
        System.out.println("InsideDivisionExpression");
    }
    public void visit(Multiplication multiplication) {
        System.out.println("InsideMultiplicationExpression");
    }
    public void visit(Subtraction subtraction) {
        System.out.println("InsideSubtractionExpression");
    }
    public void visit(AndExpression andExpression) {
        //System.out.println("InsideANDExpression");
        Expression leftExpression = andExpression.getLeftExpression();
        //System.out.println("VisitingLeftANDExpression");
        leftExpression.accept(this);
        Expression rightExpression = andExpression.getRightExpression();
        //System.out.println("VisitingRightANDExpression");
        rightExpression.accept(this);
    }
    public void visit(OrExpression orExpression) {
/*
        System.out.println("InsideORExpression");
        Expression leftExpression = orExpression.getLeftExpression();
        System.out.println("VisitingLeftANDExpression");

        Expression rightExpression = orExpression.getRightExpression();
        System.out.println("VisitingRightANDExpression");

*/
        smallJoin = bigJoin;
        bigJoin = new ArrayList<>();

        for (int i = 0; i < smallJoin.size(); i++) {
            Evaluator evaluator = new Evaluator(smallJoin.get(i), currentSchema, aliasHashMap);
            try {
                PrimitiveValue result = evaluator.eval(orExpression);
                BooleanValue boolResult = (BooleanValue)result;
                if(boolResult.getValue()) {
                    bigJoin.add(smallJoin.get(i));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }


    }
    public void visit(Between between) {
        System.out.println("InsideBetweenExpression");
    }

    public void visit(EqualsTo equalsTo) {

        Expression leftExpression = equalsTo.getLeftExpression();
        Expression rightExpression = equalsTo.getRightExpression();
        if(leftExpression instanceof Column && rightExpression instanceof Column) {

            Column col1 = (Column)leftExpression;
            Column col2 = (Column)rightExpression;

            String tableName1 = col1.getTable().getWholeTableName().toLowerCase();
            String tableName2 = col2.getTable().getWholeTableName().toLowerCase();

            tableName1 = aliasHashMap.get(tableName1);
            tableName2 = aliasHashMap.get(tableName2);

            Operator oper1 = operatorMap.get(tableName1);
            Operator oper2 = operatorMap.get(tableName2);
            PrimitiveValue[] leftTuple;
            PrimitiveValue[] rightTuple;
            PrimitiveValue[] jointTuple;
            ArrayList<PrimitiveValue[]> tempResult = new ArrayList<>();
            if(joinedTablesList.isEmpty()) {
                do {
                    leftTuple = oper1.readOneTuple();
                    if(leftTuple == null) {
                        break;
                    }
                    CreateTable ct1 = createTableMap.get(tableName1);
                    CreateTable ct2 = createTableMap.get(tableName2);

                    List cols1 = ct1.getColumnDefinitions();
                    List cols2 = ct2.getColumnDefinitions();
                    currentSchema = new Column[cols1.size() + cols2.size()];
                    for(int i = 0; i < cols1.size(); i++) {
                        ColumnDefinition col = (ColumnDefinition)cols1.get(i);
                        currentSchema[i] = new Column(new Table(null, tableName1),
                                col.getColumnName().toLowerCase());
                    }
                    for(int i = cols1.size(); i < cols1.size() + cols2.size(); i++) {
                        ColumnDefinition col = (ColumnDefinition)cols2.get(i - cols1.size());
                        currentSchema[i] = new Column(new Table(null, tableName2),
                                col.getColumnName().toLowerCase());
                    }
                    while ((rightTuple = oper2.readOneTuple()) != null) {
                        jointTuple = new PrimitiveValue[leftTuple.length + rightTuple.length];
                        for (int i = 0; i < leftTuple.length; i++) {
                            jointTuple[i] = leftTuple[i];
                        }
                        for(int i = leftTuple.length; i < leftTuple.length + rightTuple.length; i++) {
                            jointTuple[i] = rightTuple[i - leftTuple.length];
                        }
                        Evaluator evaluator = new Evaluator(jointTuple, currentSchema, aliasHashMap);
                        try {
                            PrimitiveValue result = evaluator.eval(equalsTo);
                            BooleanValue boolResult = (BooleanValue)result;
                            if(boolResult.getValue()) {
                                tempResult.add(jointTuple);
                            }
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }

                    }
                    if(rightTuple == null) {
                        oper2.reset();
                    }
                } while (tempResult.isEmpty());
                if(leftTuple == null) {
                    leftTupleNull = true;
                    oper1.reset();
                }
                bigJoin = tempResult;
                joinedTablesList.add(tableName1);
                joinedTablesList.add(tableName2);
            } else {
                smallJoin = bigJoin;
                String newTableName = null;
                Operator newOperator = null;
                if(joinedTablesList.contains(tableName1) && joinedTablesList.contains(tableName2)) {
                    for (int i = 0; i < smallJoin.size(); i++) {
                        Evaluator evaluator = new Evaluator(smallJoin.get(i), currentSchema, aliasHashMap);

                        try {
                            PrimitiveValue result = evaluator.eval(equalsTo);
                            BooleanValue boolResult = (BooleanValue)result;
                            if(boolResult.getValue()) {
                                tempResult.add(smallJoin.get(i));
                            }
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                    }
                    bigJoin = tempResult;
                    return;
                } else if(joinedTablesList.contains(tableName1)) {
                    newTableName = tableName2;
                    newOperator = oper2;
                } else if(joinedTablesList.contains(tableName2)) {
                    newTableName = tableName1;
                    newOperator = oper1;
                } else {
                    newTableName = null;
                    newOperator = null;
                    System.out.println("PANIC: I DON'T KNOW HOW TO HANDLE THIS");
                    System.exit(1);
                }

                int i = 0;
                Column[] lastJoinSchema = new Column[currentSchema.length];
                for(int j = 0; j < currentSchema.length; j++) {
                    lastJoinSchema[j] = currentSchema[j];
                }

                while (i < smallJoin.size()){
                    leftTuple = smallJoin.get(i);

                    CreateTable ct2 = createTableMap.get(newTableName);


                    List cols2 = ct2.getColumnDefinitions();
                    currentSchema = new Column[lastJoinSchema.length + cols2.size()];
                    for(int j = 0; j < lastJoinSchema.length; j++) {
                        currentSchema[j] = lastJoinSchema[j];
                    }
                    for(int j = lastJoinSchema.length; j < lastJoinSchema.length + cols2.size(); j++) {
                        ColumnDefinition col = (ColumnDefinition)cols2.get(j - lastJoinSchema.length);
                        currentSchema[j] = new Column(new Table(null, newTableName),
                                col.getColumnName().toLowerCase());
                    }
                    while ((rightTuple = newOperator.readOneTuple()) != null) {
                        jointTuple = new PrimitiveValue[leftTuple.length + rightTuple.length];
                        for (int j = 0; j < leftTuple.length; j++) {
                            jointTuple[j] = leftTuple[j];
                        }
                        for(int j = leftTuple.length; j < leftTuple.length + rightTuple.length; j++) {
                            jointTuple[j] = rightTuple[j - leftTuple.length];
                        }
                        Evaluator evaluator = new Evaluator(jointTuple, currentSchema, aliasHashMap);
                        try {
                            PrimitiveValue result = evaluator.eval(equalsTo);
                            BooleanValue boolResult = (BooleanValue)result;
                            if(boolResult.getValue()) {
                                tempResult.add(jointTuple);
                            }
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                    }
                    if(rightTuple == null) {
                        newOperator.reset();
                    }
                    i++;
                }

                bigJoin = tempResult;
                joinedTablesList.add(newTableName);
            }

        } else {

            Column col1;
            if(leftExpression instanceof Column) {
                col1 = (Column)leftExpression;
            } else {
                col1 = (Column)rightExpression;
            }
            String tableName = null;

            tableName = col1.getTable().getWholeTableName().toLowerCase();

            tableName = aliasHashMap.get(tableName);
/*
            if(tableName == null) {
                for(int i = 0; i < currentSchema.length; i++) {
                    if(col1.getColumnName().toLowerCase().equals(currentSchema[i].getColumnName().toLowerCase())) {
                        tableName = currentSchema[i].getTable().getWholeTableName().toLowerCase();
                        break;
                    }
                }
            }
*/
            smallJoin = bigJoin;
            bigJoin = new ArrayList<>();

            if(joinedTablesList.contains(tableName)) {

                for (int i = 0; i < smallJoin.size(); i++) {
                    Evaluator evaluator = new Evaluator(smallJoin.get(i), currentSchema, aliasHashMap);

                    try {
                        PrimitiveValue result = evaluator.eval(equalsTo);
                        BooleanValue boolResult = (BooleanValue)result;
                        if(boolResult.getValue()) {
                            bigJoin.add(smallJoin.get(i));
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }
            } else {
                System.out.println("PANIC Equal To Expression: Table not found in  JoinedTableList");
            }

        }
    }
    public void visit(GreaterThan greaterThan) {

        Expression leftExpression = greaterThan.getLeftExpression();
        Expression rightExpression = greaterThan.getLeftExpression();

        Column col1;
        if(leftExpression instanceof Column) {
            col1 = (Column)leftExpression;
        } else {
            col1 = (Column)rightExpression;
        }

        String tableName = col1.getTable().getWholeTableName().toLowerCase();

        tableName = aliasHashMap.get(tableName);

        smallJoin = bigJoin;
        bigJoin = new ArrayList<>();

        if(joinedTablesList.contains(tableName)) {

            for (int i = 0; i < smallJoin.size(); i++) {
                Evaluator evaluator = new Evaluator(smallJoin.get(i), currentSchema, aliasHashMap);

                try {
                    PrimitiveValue result = evaluator.eval(greaterThan);
                    BooleanValue boolResult = (BooleanValue)result;
                    if(boolResult.getValue()) {
                        bigJoin.add(smallJoin.get(i));
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        } else {
            System.out.println("PANIC GreaterThan Expression: Table not found in  JoinedTableList");
        }
    }
    public void visit(GreaterThanEquals greaterThanEquals) {

        Expression leftExpression = greaterThanEquals.getLeftExpression();
        Expression rightExpression = greaterThanEquals.getLeftExpression();

        Column col1;
        if(leftExpression instanceof Column) {
            col1 = (Column)leftExpression;
        } else {
            col1 = (Column)rightExpression;
        }

        String tableName = col1.getTable().getWholeTableName().toLowerCase();

        tableName = aliasHashMap.get(tableName);

        smallJoin = bigJoin;
        bigJoin = new ArrayList<>();

        if(joinedTablesList.contains(tableName)) {

            for (int i = 0; i < smallJoin.size(); i++) {
                Evaluator evaluator = new Evaluator(smallJoin.get(i), currentSchema, aliasHashMap);

                try {
                    PrimitiveValue result = evaluator.eval(greaterThanEquals);
                    BooleanValue boolResult = (BooleanValue)result;
                    if(boolResult.getValue()) {
                        bigJoin.add(smallJoin.get(i));
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        } else {
            System.out.println("PANIC GreaterThanEquals Expression: Table not found in  JoinedTableList");
        }
    }
    public void visit(InExpression inExpression)
    {

        smallJoin = bigJoin;
        bigJoin = new ArrayList<>();

        Expression leftExpression = inExpression.getLeftExpression();
        ItemsList itemsList = inExpression.getItemsList();
        ArrayList<PrimitiveValue[]> tuplesList = new ArrayList<>();
        Column[] schema = null;
        if(itemsList instanceof ExpressionList) {
            System.out.println("PANIC: I CAN'T HANDLE EXPRESSIONS IN IN CLAUSE");
            System.exit(1);
        } else if (itemsList instanceof SubSelect) {
            SelectBody selectBody = ((SubSelect) itemsList).getSelectBody();
            if(selectBody instanceof PlainSelect){
                String alias = "IN";
                PlainSelect plainSelect = (PlainSelect) selectBody;
                SubselectEvaluator subselect = new SubselectEvaluator(
                        plainSelect, createTableMap, alias
                );
                subselect.execute();
                schema = subselect.schema;
                PrimitiveValue tuple[] = new PrimitiveValue[schema.length];
                while ((tuple = subselect.readOneTuple()) != null) {
                    tuplesList.add(tuple);
                }
                subselect.reset();
            }
        }



        Column col = (Column)leftExpression;
        String colName = col.getWholeColumnName().toLowerCase();
        int myIndex = 0;
        int index = 0;
        for (int i = 0; i < currentSchema.length; i++) {
            if(colName.equals(currentSchema[i].getWholeColumnName().toLowerCase())) {
                myIndex = i;
                break;
            }
        }

        for (int i = 0; i < schema.length; i++) {
            if(colName.equals(schema[i].getWholeColumnName().toLowerCase())) {
                index = i;
                break;
            }
        }

        for (int i = 0; i < smallJoin.size(); i++) {
            PrimitiveValue[] temp = smallJoin.get(i);
            String searchString = temp[myIndex].toString();

            for (int j = 0; j < tuplesList.size(); j++) {
                if(searchString.equals(tuplesList.get(j)[index].toString())) {
                    bigJoin.add(temp);
                    break;
                }
            }
        }
    }
    public void visit(IsNullExpression isNullExpression) {
        System.out.println("InsideIsNullExpression");
    }
    public void visit(LikeExpression likeExpression) {
        Expression leftExpression = likeExpression.getLeftExpression();

		Column col1 = (Column)leftExpression;

		String tableName = col1.getTable().getWholeTableName().toLowerCase();

		tableName = aliasHashMap.get(tableName);

        smallJoin = bigJoin;
        bigJoin = new ArrayList<>();

        if(joinedTablesList.contains(tableName)) {

            for (int i = 0; i < smallJoin.size(); i++) {
                Evaluator evaluator = new Evaluator(smallJoin.get(i), currentSchema, aliasHashMap);

                try {
                    PrimitiveValue result = evaluator.eval(likeExpression);
                    BooleanValue boolResult = (BooleanValue)result;
                    if(boolResult.getValue()) {
                        bigJoin.add(smallJoin.get(i));
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        } else {
            System.out.println("PANIC Like Expression: Table not found in  JoinedTableList");
        }
    }
    public void visit(MinorThan minorThan) {

        Expression leftExpression = minorThan.getLeftExpression();
        Expression rightExpression = minorThan.getLeftExpression();

        Column col1;
        if(leftExpression instanceof Column) {
            col1 = (Column)leftExpression;
        } else {
            col1 = (Column)rightExpression;
        }

        String tableName = col1.getTable().getWholeTableName().toLowerCase();

        tableName = aliasHashMap.get(tableName);

        smallJoin = bigJoin;
        bigJoin = new ArrayList<>();

        if(joinedTablesList.contains(tableName)) {

            for (int i = 0; i < smallJoin.size(); i++) {
                Evaluator evaluator = new Evaluator(smallJoin.get(i), currentSchema, aliasHashMap);

                try {
                    PrimitiveValue result = evaluator.eval(minorThan);
                    BooleanValue boolResult = (BooleanValue)result;
                    if(boolResult.getValue()) {
                        bigJoin.add(smallJoin.get(i));
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        } else {
            System.out.println("PANIC MinorThan Expression: Table not found in  JoinedTableList");
        }
    }
    public void visit(MinorThanEquals minorThanEquals)
    {

        Expression leftExpression = minorThanEquals.getLeftExpression();
        Expression rightExpression = minorThanEquals.getLeftExpression();

        Column col1;
        if(leftExpression instanceof Column) {
            col1 = (Column)leftExpression;
        } else {
            col1 = (Column)rightExpression;
        }

        String tableName = col1.getTable().getWholeTableName().toLowerCase();

        tableName = aliasHashMap.get(tableName);

        smallJoin = bigJoin;
        bigJoin = new ArrayList<>();

        if(joinedTablesList.contains(tableName)) {

            for (int i = 0; i < smallJoin.size(); i++) {
                Evaluator evaluator = new Evaluator(smallJoin.get(i), currentSchema, aliasHashMap);

                try {
                    PrimitiveValue result = evaluator.eval(minorThanEquals);
                    BooleanValue boolResult = (BooleanValue)result;
                    if(boolResult.getValue()) {
                        bigJoin.add(smallJoin.get(i));
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        } else {
            System.out.println("PANIC MinorThanEquals Expression: Table not found in  JoinedTableList");
        }
    }
    public void visit(NotEqualsTo notEqualsTo) {

        Expression leftExpression = notEqualsTo.getLeftExpression();
        Expression rightExpression = notEqualsTo.getLeftExpression();

        Column col1;
        if(leftExpression instanceof Column) {
            col1 = (Column)leftExpression;
        } else {
            col1 = (Column)rightExpression;
        }

        String tableName = col1.getTable().getWholeTableName().toLowerCase();

        tableName = aliasHashMap.get(tableName);

        smallJoin = bigJoin;
        bigJoin = new ArrayList<>();

        if(joinedTablesList.contains(tableName)) {

            for (int i = 0; i < smallJoin.size(); i++) {
                Evaluator evaluator = new Evaluator(smallJoin.get(i), currentSchema, aliasHashMap);

                try {
                    PrimitiveValue result = evaluator.eval(notEqualsTo);
                    BooleanValue boolResult = (BooleanValue)result;
                    if(boolResult.getValue()) {
                        bigJoin.add(smallJoin.get(i));
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        } else {
            System.out.println("PANIC NotEqualsTo Expression: Table not found in  JoinedTableList");
        }
    }
    public void visit(Column tableColumn) {
        System.out.println("InsideTableColumnExpression");
    }
    public void visit(SubSelect subSelect) {
        System.out.println("InsideSubSelectExpression");
    }
    public void visit(CaseExpression caseExpression) {
        System.out.println("InsideCaseExpression");
    }
    public void visit(WhenClause whenClause) {
        System.out.println("InsideWhenClauseExpression");
    }
    public void visit(ExistsExpression existsExpression) {
        System.out.println("InsideExistsExpression");
    }
    public void visit(AllComparisonExpression allComparisonExpression) {
        System.out.println("InsideAllComparisonExpression");
    }
    public void visit(AnyComparisonExpression anyComparisonExpression) {
        System.out.println("InsideAnyComparisonExpression");
    }
    public void visit(Concat concat) {
        System.out.println("InsideConcatExpression");
    }
    public void visit(Matches matches) {
        System.out.println("InsideMatchesExpression");
    }
    public void visit(BitwiseAnd bitwiseAnd) {
        System.out.println("InsideBitwiseAndExpression");
    }
    public void visit(BitwiseOr bitwiseOr) {
        System.out.println("InsideBitwiseORExpression");
    }
    public void visit(BitwiseXor bitwiseXor) {
        System.out.println("InsideBitwiseXORExpression");
    }
}

