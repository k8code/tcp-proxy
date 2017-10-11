package io.mycat.proxy.man.packet;

import io.mycat.mycat2.beans.conf.ProxyConfig;
import io.mycat.proxy.ConfigEnum;
import io.mycat.proxy.ProxyBuffer;
import io.mycat.proxy.ProxyRuntime;
import io.mycat.proxy.man.ManagePacket;
import io.mycat.proxy.man.MyCluster;
import io.mycat.proxy.man.MyCluster.ClusterState;

/**
 * 节点信息的报文，用于向对方表明自己的身份信息以及自己所处的集群状态
 * 
 * @author wuzhihui
 *
 */
public class NodeRegInfoPacket extends ManagePacket {
	private String nodeId;
	private ClusterState clusterState;
	private long lastClusterStateTime;
	private String myLeader;
	private int proxyPort;
	private long startupTime;
	//是否应答之前的NodeRegInfo报文
	private boolean isAnswer;

	public NodeRegInfoPacket(String nodeId, MyCluster.ClusterState myClusterState,long lastClusterStateTime,String myLeader,long startupTime) {
		super(ManagePacket.PKG_NODE_REG);
		this.nodeId = nodeId;
		this.clusterState=myClusterState;
		this.lastClusterStateTime=lastClusterStateTime;
		setMyLeader(myLeader);
		this.startupTime = startupTime;
		ProxyConfig proxyConfig = ProxyRuntime.INSTANCE.getConfig().getConfig(ConfigEnum.PROXY);
		this.proxyPort = proxyConfig.getProxy().getPort();
	}

	public NodeRegInfoPacket() {
		this(null, MyCluster.ClusterState.Joining,0,null,0);
	}

	@Override
	public void resolveBody(ProxyBuffer buffer) {
		nodeId = buffer.readNULString();
		this.clusterState=ClusterState.getState(buffer.readByte());
		this.lastClusterStateTime=buffer.readFixInt(8);
		this.myLeader=buffer.readNULString();
		myLeader=myLeader.equals("")?null:myLeader;
		proxyPort = (int) buffer.readFixInt(4);
		startupTime = buffer.readFixInt(8);
		isAnswer=buffer.readByte()==0x01;

	}

	@Override
	public void writeBody(ProxyBuffer buffer) {
		buffer.writeNULString(nodeId);
		buffer.writeByte(clusterState.getSateCode());
		buffer.writeFixInt(8, this.lastClusterStateTime);
		buffer.writeNULString(myLeader);
		buffer.writeFixInt(4, proxyPort);
		buffer.writeFixInt(8, startupTime);
		buffer.writeByte((byte) (isAnswer?0x01:0x00));

	}

	public long getLastClusterStateTime() {
		return lastClusterStateTime;
	}

	public void setLastClusterStateTime(long lastClusterStateTime) {
		this.lastClusterStateTime = lastClusterStateTime;
	}

	public String getNodeId() {
		return nodeId;
	}

	public void setNodeId(String nodeId) {
		this.nodeId = nodeId;
	}

	public long getStartupTime() {
		return startupTime;
	}

	public void setStartupTime(long startupTime) {
		this.startupTime = startupTime;
	}

	public boolean isAnswer() {
		return isAnswer;
	}

	public void setAnswer(boolean isAnswer) {
		this.isAnswer = isAnswer;
	}

	public ClusterState getClusterState() {
		return clusterState;
	}

	public void setClusterState(ClusterState clusterState) {
		this.clusterState = clusterState;
	}

	public String getMyLeader() {
		return myLeader;
	}

	public void setMyLeader(String myLeader) {
		this.myLeader = myLeader==null?"":myLeader;
	}

	public int getProxyPort() {
		return proxyPort;
	}

	public void setProxyPort(int proxyPort) {
		this.proxyPort = proxyPort;
	}
}
