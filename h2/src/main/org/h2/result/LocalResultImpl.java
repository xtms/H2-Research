/*
 * Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.result;

import java.util.ArrayList;
import java.util.Arrays;
import org.h2.engine.Database;
import org.h2.engine.Session;
import org.h2.engine.SessionInterface;
import org.h2.expression.Expression;
import org.h2.message.DbException;
import org.h2.mvstore.db.MVTempResult;
import org.h2.util.Utils;
import org.h2.util.ValueHashMap;
import org.h2.value.Value;
import org.h2.value.ValueArray;

/**
 * A local result set contains all row data of a result set.
 * This is the object generated by engine,
 * and it is also used directly by the ResultSet class in the embedded mode.
 * If the result does not fit in memory, it is written to a temporary file.
 */
public class LocalResultImpl implements LocalResult {

    private int maxMemoryRows;
    private Session session;
    private int visibleColumnCount;
    private Expression[] expressions;
    private int rowId, rowCount;
    private ArrayList<Value[]> rows;
    private SortOrder sort;
    private ValueHashMap<Value[]> distinctRows;
    private Value[] currentRow;
    private int offset;
    private int limit = -1;
    private boolean fetchPercent;
    private boolean withTies;
    private boolean limitsWereApplied;
    private ResultExternal external;
    private boolean distinct;
    private int[] distinctIndexes;
    private boolean closed;
    private boolean containsLobs;

    /**
     * Construct a local result object.
     */
    public LocalResultImpl() {
        // nothing to do
    }

    /**
     * Construct a local result object.
     *
     * @param session the session
     * @param expressions the expression array
     * @param visibleColumnCount the number of visible columns
     */
    public LocalResultImpl(Session session, Expression[] expressions,
            int visibleColumnCount) {
        this.session = session;
        if (session == null) {
            this.maxMemoryRows = Integer.MAX_VALUE;
        } else {
            Database db = session.getDatabase();
            if (db.isPersistent() && !db.isReadOnly()) {
                this.maxMemoryRows = session.getDatabase().getMaxMemoryRows();
            } else {
                this.maxMemoryRows = Integer.MAX_VALUE;
            }
        }
        rows = Utils.newSmallArrayList();
        this.visibleColumnCount = visibleColumnCount;
        rowId = -1;
        this.expressions = expressions;
    }

    @Override
    public boolean isLazy() {
        return false;
    }

    @Override
    public void setMaxMemoryRows(int maxValue) {
        this.maxMemoryRows = maxValue;
    }

    /**
     * Create a shallow copy of the result set. The data and a temporary table
     * (if there is any) is not copied.
     *
     * @param targetSession the session of the copy
     * @return the copy if possible, or null if copying is not possible
     */
    @Override
    public LocalResultImpl createShallowCopy(SessionInterface targetSession) {
        if (external == null && (rows == null || rows.size() < rowCount)) {
            return null;
        }
        if (containsLobs) {
            return null;
        }
        ResultExternal e2 = null;
        if (external != null) {
            e2 = external.createShallowCopy();
            if (e2 == null) {
                return null;
            }
        }
        LocalResultImpl copy = new LocalResultImpl();
        copy.maxMemoryRows = this.maxMemoryRows;
        copy.session = (Session) targetSession;
        copy.visibleColumnCount = this.visibleColumnCount;
        copy.expressions = this.expressions;
        copy.rowId = -1;
        copy.rowCount = this.rowCount;
        copy.rows = this.rows;
        copy.sort = this.sort;
        copy.distinctRows = this.distinctRows;
        copy.distinct = distinct;
        copy.distinctIndexes = distinctIndexes;
        copy.currentRow = null;
        copy.offset = 0;
        copy.limit = -1;
        copy.external = e2;
        return copy;
    }

    /**
     * Set the sort order.
     *
     * @param sort the sort order
     */
    @Override
    public void setSortOrder(SortOrder sort) {
        this.sort = sort;
    }

    /**
     * Remove duplicate rows.
     */
    @Override
    public void setDistinct() {
        assert distinctIndexes == null;
        distinct = true;
        distinctRows = new ValueHashMap<>();
    }

    /**
     * Remove rows with duplicates in columns with specified indexes.
     *
     * @param distinctIndexes distinct indexes
     */
    @Override
    public void setDistinct(int[] distinctIndexes) {
        assert !distinct;
        this.distinctIndexes = distinctIndexes;
        distinctRows = new ValueHashMap<>();
    }

    /**
     * @return whether this result is a distinct result
     */
    private boolean isAnyDistinct() {
        return distinct || distinctIndexes != null;
    }

    /**
     * Remove the row from the result set if it exists.
     *
     * @param values the row
     */
    @Override
    public void removeDistinct(Value[] values) {
        if (!distinct) {
            DbException.throwInternalError();
        }
        assert values.length == visibleColumnCount;
        if (distinctRows != null) {
            ValueArray array = ValueArray.get(values);
            distinctRows.remove(array);
            rowCount = distinctRows.size();
        } else {
            rowCount = external.removeRow(values);
        }
    }

    /**
     * Check if this result set contains the given row.
     *
     * @param values the row
     * @return true if the row exists
     */
    @Override
    public boolean containsDistinct(Value[] values) {
        assert values.length == visibleColumnCount;
        if (external != null) {
            return external.contains(values);
        }
        if (distinctRows == null) {
            distinctRows = new ValueHashMap<>();
            for (Value[] row : rows) {
                ValueArray array = getArrayOfDistinct(row);
                distinctRows.put(array, array.getList());
            }
        }
        ValueArray array = ValueArray.get(values);
        return distinctRows.get(array) != null;
    }

    @Override
    public void reset() {
        rowId = -1;
        currentRow = null;
        if (external != null) {
            external.reset();
        }
    }

    @Override
    public Value[] currentRow() {
        return currentRow;
    }

    @Override
    public boolean next() {
        if (!closed && rowId < rowCount) {
            rowId++;
            if (rowId < rowCount) {
                if (external != null) {
                    currentRow = external.next();
                } else {
                    currentRow = rows.get(rowId);
                }
                return true;
            }
            currentRow = null;
        }
        return false;
    }

    @Override
    public int getRowId() {
        return rowId;
    }

    @Override
    public boolean isAfterLast() {
        return rowId >= rowCount;
    }

    private void cloneLobs(Value[] values) {
        for (int i = 0; i < values.length; i++) {
            Value v = values[i];
            Value v2 = v.copyToResult();
            if (v2 != v) {
                containsLobs = true;
                session.addTemporaryLob(v2);
                values[i] = v2;
            }
        }
    }

    private ValueArray getArrayOfDistinct(Value[] values) {
        if (distinctIndexes != null) {
            int cnt = distinctIndexes.length;
            Value[] newValues = new Value[cnt];
            for (int i = 0; i < cnt; i++) {
                newValues[i] = values[distinctIndexes[i]];
            }
            values = newValues;
        } else if (values.length > visibleColumnCount) {
            values = Arrays.copyOf(values, visibleColumnCount);
        }
        return ValueArray.get(values);
    }

    private void createExternalResult() {
        Database database = session.getDatabase();
        external = database.isMVStore()
                || /* not supported by ResultTempTable */ distinct && expressions.length != visibleColumnCount
                || distinctIndexes != null
                ? MVTempResult.of(database, expressions, distinct, distinctIndexes, visibleColumnCount, sort)
                        : new ResultTempTable(session, expressions, distinct, sort);
    }

    /**
     * Add a row to this object.
     *
     * @param values the row to add
     */
    @Override
    public void addRow(Value[] values) {
        cloneLobs(values);
        if (isAnyDistinct()) {
            if (distinctRows != null) {
                ValueArray array = getArrayOfDistinct(values);
                distinctRows.putIfAbsent(array, values);
                rowCount = distinctRows.size();
                if (rowCount > maxMemoryRows) {
                    createExternalResult();
                    rowCount = external.addRows(distinctRows.values());
                    distinctRows = null;
                }
            } else {
                rowCount = external.addRow(values);
            }
        } else {
            rows.add(values);
            rowCount++;
            if (rows.size() > maxMemoryRows) {
                addRowsToDisk();
            }
        }
    }

    private void addRowsToDisk() {
        if (external == null) {
            createExternalResult();
        }
        rowCount = external.addRows(rows);
        rows.clear();
    }

    @Override
    public int getVisibleColumnCount() {
        return visibleColumnCount;
    }

    /**
     * This method is called after all rows have been added.
     */
    @Override
    public void done() {
        if (external != null) {
            addRowsToDisk();
        } else {
            if (isAnyDistinct()) {
                rows = distinctRows.values();
            }
            if (sort != null && limit != 0) {
                boolean withLimit = limit > 0 && !withTies;
                if (offset > 0 || withLimit) {
                    sort.sort(rows, offset, withLimit ? limit : rows.size());
                } else {
                    sort.sort(rows);
                }
            }
        }
        applyOffsetAndLimit();
        reset();
    }

    private void applyOffsetAndLimit() {
        if (limitsWereApplied) {
            return;
        }
        int offset = Math.max(this.offset, 0);
        int limit = this.limit;
        if (offset == 0 && limit < 0 && !fetchPercent || rowCount == 0) {
            return;
        }
        if (fetchPercent) {
            if (limit < 0 || limit > 100) {
                throw DbException.getInvalidValueException("FETCH PERCENT", limit);
            }
            // Oracle rounds percent up, do the same for now
            limit = (int) (((long) limit * rowCount + 99) / 100);
        }
        boolean clearAll = offset >= rowCount || limit == 0;
        if (!clearAll) {
            int remaining = rowCount - offset;
            limit = limit < 0 ? remaining : Math.min(remaining, limit);
            if (offset == 0 && remaining <= limit) {
                return;
            }
        } else {
            limit = 0;
        }
        distinctRows = null;
        rowCount = limit;
        if (external == null) {
            if (clearAll) {
                rows.clear();
                return;
            }
            int to = offset + limit;
            if (withTies && sort != null) {
                Value[] expected = rows.get(to - 1);
                while (to < rows.size() && sort.compare(expected, rows.get(to)) == 0) {
                    to++;
                    rowCount++;
                }
            }
            if (offset != 0 || to != rows.size()) {
                // avoid copying the whole array for each row
                rows = new ArrayList<>(rows.subList(offset, to));
            }
        } else {
            if (clearAll) {
                external.close();
                external = null;
                return;
            }
            trimExternal(offset, limit);
        }
    }

    private void trimExternal(int offset, int limit) {
        ResultExternal temp = external;
        external = null;
        temp.reset();
        while (--offset >= 0) {
            temp.next();
        }
        Value[] row = null;
        while (--limit >= 0) {
            row = temp.next();
            rows.add(row);
            if (rows.size() > maxMemoryRows) {
                addRowsToDisk();
            }
        }
        if (withTies && sort != null && row != null) {
            Value[] expected = row;
            while ((row = temp.next()) != null && sort.compare(expected, row) == 0) {
                rows.add(row);
                rowCount++;
                if (rows.size() > maxMemoryRows) {
                    addRowsToDisk();
                }
            }
        }
        if (external != null) {
            addRowsToDisk();
        }
        temp.close();
    }

    @Override
    public int getRowCount() {
        return rowCount;
    }

    @Override
    public void limitsWereApplied() {
        this.limitsWereApplied = true;
    }

    @Override
    public boolean hasNext() {
        return !closed && rowId < rowCount - 1;
    }

    /**
     * Set the number of rows that this result will return at the maximum.
     *
     * @param limit the limit (-1 means no limit, 0 means no rows)
     */
    @Override
    public void setLimit(int limit) {
        this.limit = limit;
    }

    /**
     * @param fetchPercent whether limit expression specifies percentage of rows
     */
    @Override
    public void setFetchPercent(boolean fetchPercent) {
        this.fetchPercent = fetchPercent;
    }

    /**
     * @param withTies whether tied rows should be included in result too
     */
    @Override
    public void setWithTies(boolean withTies) {
        this.withTies = withTies;
    }

    @Override
    public boolean needToClose() {
        return external != null;
    }

    @Override
    public void close() {
        if (external != null) {
            external.close();
            external = null;
            closed = true;
        }
    }

    @Override
    public String getAlias(int i) {
        return expressions[i].getAlias();
    }

    @Override
    public String getTableName(int i) {
        return expressions[i].getTableName();
    }

    @Override
    public String getSchemaName(int i) {
        return expressions[i].getSchemaName();
    }

    @Override
    public int getDisplaySize(int i) {
        return expressions[i].getDisplaySize();
    }

    @Override
    public String getColumnName(int i) {
        return expressions[i].getColumnName();
    }

    @Override
    public int getColumnType(int i) {
        return expressions[i].getType();
    }

    @Override
    public long getColumnPrecision(int i) {
        return expressions[i].getPrecision();
    }

    @Override
    public int getNullable(int i) {
        return expressions[i].getNullable();
    }

    @Override
    public boolean isAutoIncrement(int i) {
        return expressions[i].isAutoIncrement();
    }

    @Override
    public int getColumnScale(int i) {
        return expressions[i].getScale();
    }

    /**
     * Set the offset of the first row to return.
     *
     * @param offset the offset
     */
    @Override
    public void setOffset(int offset) {
        this.offset = offset;
    }

    @Override
    public String toString() {
        return super.toString() + " columns: " + visibleColumnCount +
                " rows: " + rowCount + " pos: " + rowId;
    }

    /**
     * Check if this result set is closed.
     *
     * @return true if it is
     */
    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public int getFetchSize() {
        return 0;
    }

    @Override
    public void setFetchSize(int fetchSize) {
        // ignore
    }

}
