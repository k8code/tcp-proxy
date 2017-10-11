package io.mycat.mycat2.beans.conf;

/**
 * Desc:
 *
 * @date: 24/09/2017
 * @author: gaozhiwen
 */
public class TableDefBean {
    public enum TableTypeEnum {
        MASTER, SLAVE;
    }

    private String name;
    private TableTypeEnum tableType;
    private String shardingKey;
    private String shardingRule;
    private String store;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TableTypeEnum getTableType() {
        return tableType;
    }

    public void setTableType(TableTypeEnum tableType) {
        this.tableType = tableType;
    }

    public String getShardingKey() {
        return shardingKey;
    }

    public void setShardingKey(String shardingKey) {
        this.shardingKey = shardingKey;
    }

    public String getShardingRule() {
        return shardingRule;
    }

    public void setShardingRule(String shardingRule) {
        this.shardingRule = shardingRule;
    }

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    @Override
    public String toString() {
        return "TableDefBean [name=" + name + ", tableType=" + tableType + ", store=" + store + ", shardingKey=" + shardingKey + ", shardingRule="
                + shardingRule + "]";
    }
}
