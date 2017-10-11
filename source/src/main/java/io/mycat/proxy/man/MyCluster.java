package io.mycat.proxy.man;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import io.mycat.mycat2.ProxyStarter;
import io.mycat.mycat2.beans.conf.ProxyConfig;
import io.mycat.proxy.ConfigEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mycat.proxy.ProxyRuntime;
import io.mycat.proxy.Session;
import io.mycat.proxy.man.ClusterNode.NodeState;
import io.mycat.proxy.man.packet.JoinCLusterReqPacket;
import io.mycat.proxy.man.packet.JoinCLusterNotifyPacket;
import io.mycat.proxy.man.packet.NodeRegInfoPacket;

/**
 * Mycat集群，保存了当前节点成员以及状态
 * 
 * @author wuzhihui
 *
 */
public class MyCluster {
	private static Logger logger = LoggerFactory.getLogger(MyCluster.class);

	public enum ClusterState {
		Joining(0), LeaderElection(1), Clustered(2);
		private byte stateCode;

		ClusterState(int stateCode) {
			this.stateCode = (byte) stateCode;
		}

		public static ClusterState getState(byte stateCode) {
			return ClusterState.values()[stateCode];
		}

		public byte getSateCode() {
			return this.stateCode;
		}
	}

	public ConcurrentHashMap<String, ClusterNode> allNodes = new ConcurrentHashMap<>();
	private final ClusterNode myNode;
	private ClusterNode myLeader;
	private ClusterState clusterState = ClusterState.Joining;
	private long lastClusterStateTime;
	private final Selector nioSelector;

	public Map<Byte, ConfigConfirmBean> configConfirmMap = new LinkedHashMap<>();

	public MyCluster(Selector nioSelector, String myNodeId, ArrayList<ClusterNode> allClusterNodes) {
		this.nioSelector = nioSelector;
		ClusterNode myNode = null;
		for (ClusterNode node : allClusterNodes) {
			if (myNodeId.equals(node.id)) {
				myNode = node;
			}
			allNodes.put(node.id, node);
		}
		myNode.setState(NodeState.Online);
		this.myNode = myNode;
		ProxyConfig proxyConfig = ProxyRuntime.INSTANCE.getConfig().getConfig(ConfigEnum.PROXY);
		this.myNode.proxyPort = proxyConfig.getProxy().getPort();
	}

	/**
	 * 初始化集群
	 */
	public void initCluster() {
		ClusterNode[] nodes = new ClusterNode[allNodes.size()];
		allNodes.values().toArray(nodes);
		// 排序结果，节点最大的在数组第一个位置
		Arrays.sort(nodes);
		for (ClusterNode curNode : nodes) {
			if (curNode.equals(myNode))
				continue;
			try {
				logger.info("Connecting to MycatNode " + curNode);
				InetSocketAddress serverAddress = new InetSocketAddress(curNode.ip, curNode.port);
				SocketChannel socketChannel = SocketChannel.open();
				socketChannel.configureBlocking(false);
				socketChannel.register(nioSelector, SelectionKey.OP_CONNECT, curNode.id);
				socketChannel.connect(serverAddress);
			} catch (Exception e) {
				logger.warn("connect err " + e);
			}
		}
	}

	/**
	 * 发送心跳包到已有节点，并且对连接失败的节点重新发起连接，以维持集群状态
	 */
	public void heatbeateAndReconnectNodes() {

	}

	public void onClusterNodeUp(NodeRegInfoPacket pkg, AdminSession session) throws IOException {
		String theNodeId = pkg.getNodeId();
		ClusterNode theNode = allNodes.get(theNodeId);
		theNode.proxyPort = pkg.getProxyPort();
		theNode.setNodeStartTime(pkg.getStartupTime());
		theNode.setState(NodeState.Online);
		theNode.setMyLeaderId(pkg.getMyLeader());
		theNode.setMyClusterState(pkg.getClusterState(), pkg.getLastClusterStateTime());
		logger.info("Node online {} at {}:{} started at {} cluster leader {} cluster state {} cluster time:{} proxy port:{}",
				theNode.id, theNode.ip, theNode.port, new Timestamp(theNode.getNodeStartTime()), theNode.getMyLeaderId(),
				theNode.getMyClusterState(), theNode.getLastClusterStateTime(), theNode.proxyPort);

		if (clusterState == ClusterState.Joining || clusterState == ClusterState.LeaderElection) {
			if (theNode.getMyClusterState() == ClusterState.Clustered) {
				myLeader = this.findNode(theNode.getMyLeaderId());
				if (myLeader.getState() == NodeState.Online) {
					logger.info("send join cluster message to leader " + theNode.id);
					// 向Leader发送请求加入集群的报文
					JoinCLusterReqPacket joinPkg = new JoinCLusterReqPacket(getMyAliveNodes());
					findSession(myLeader.id).answerClientNow(joinPkg);
				}
			} else if (checkIfLeader()) {
				// 是连接中当前编号最小的节点，当选为Leader
				logger.info("I'm smallest alive node, and exceeded 1/2 nodes alive, so I'm the King now!");
				// 集群主已产生，继续加载配置，提供服务
				ProxyStarter.INSTANCE.startProxy(true);

				this.setClusterState(ClusterState.Clustered);
				this.myNode.setMyLeaderId(getMyNodeId());
				this.myLeader = this.myNode;
				JoinCLusterNotifyPacket joinReps = createJoinNotifyPkg(session,JoinCLusterNotifyPacket.JOIN_STATE_NEED_ACK);
				notifyAllNodes(session,joinReps);
			} else if (theNode.getMyClusterState() == ClusterState.LeaderElection) {
				setClusterState(ClusterState.LeaderElection);
			}
		} else if (myLeader == myNode) {// 当前是集群状态，并且自己是Leader，就回话，可以加入集群
			session.answerClientNow(createJoinNotifyPkg(session,JoinCLusterNotifyPacket.JOIN_STATE_NEED_ACK));
		}
	}

	private boolean checkIfLeader() {
		int halfAllCount = allNodes.size() >> 1;
		int myAliveCount = getMyAliveNodesCount();
		if (nodesIsOdd()) {
			//奇数个节点，判断是否>一半
			return (myAliveCount > halfAllCount && imSmallestAliveNode());
		} else {
			//偶数个节点，判断是否>一半 或者 =一半时主节点是集群中最小的节点
			return (myAliveCount > halfAllCount && imSmallestAliveNode()) || (myAliveCount == halfAllCount && imSmallestInNodes());
		}
	}

	private void notifyAllNodes(AdminSession session,ManagePacket packet) {
		for (Session theSession : session.getMySessionManager().getAllSessions()) {
			AdminSession nodeSession = (AdminSession) theSession;
			if (nodeSession.isChannelOpen()) {
				try {
					nodeSession.answerClientNow(packet);
				} catch (Exception e) {
					logger.warn("notify node err " + nodeSession.getNodeId(),e);
				}
			}
		}
	}

	private JoinCLusterNotifyPacket createJoinNotifyPkg(AdminSession session,byte joinState) {
		JoinCLusterNotifyPacket respPacket = new JoinCLusterNotifyPacket(session.cluster().getMyAliveNodes());
		respPacket.setJoinState(joinState);
		return respPacket;
	}

	/**
	 * 判断当前节点是否是在online状态下最小的节点
     */
	private boolean imSmallestAliveNode() {
		String myId = this.getMyNodeId();
		for (ClusterNode curNode : this.allNodes.values()) {
			if (curNode.getState() == NodeState.Online) {
				if (myId.compareTo(curNode.id) > 0) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * 判断当前节点是否是在所有配置中最小的节点
     */
	private boolean imSmallestInNodes() {
		String myId = this.getMyNodeId();
		for (ClusterNode curNode : this.allNodes.values()) {
			if (myId.compareTo(curNode.id) > 0) {
				return false;
			}
		}
		return true;
	}

	public String getMyAliveNodes() {
		StringBuilder sb = new StringBuilder();
		for (ClusterNode curNode : this.allNodes.values()) {
			if (curNode.getState() == NodeState.Online) {
				sb.append(curNode.id).append(",");
			}
		}
		if (sb.length() > 0) {
			sb.deleteCharAt(sb.length() - 1);
		}
		return sb.toString();
	}

	private int getMyAliveNodesCount() {
		int aliveCount = 0;
		for (ClusterNode curNode : this.allNodes.values()) {
			if (curNode.getState() == NodeState.Online) {
				aliveCount++;
			}
		}
		return aliveCount;
	}

	private AdminSession findSession(String nodeId) {
		for (AdminSession session : ProxyRuntime.INSTANCE.getAdminSessionManager().getAllSessions()) {
			if (session.getNodeId().equals(nodeId)) {
				return session;
			}
		}
		return null;
	}

	public void onClusterNodeDown(String nodeId, AdminSession session) throws IOException {
		ClusterNode theNode = allNodes.get(nodeId);
		theNode.setState(NodeState.Offline);
		logger.info("Node offline {} at {}:{} started at {}", theNode.id, theNode.ip, theNode.port, new Timestamp(theNode.getNodeStartTime()));

		if (theNode == myLeader) {
			if (checkIfLeader()) {
				logger.info("My Leader crashed, I'm smallest alive node, and exceeded 1/2 nodes alive, so I'm the King now!");
				this.setClusterState(ClusterState.Clustered);
				this.myNode.setMyLeaderId(getMyNodeId());
				this.myLeader = this.myNode;

				// 集群主切换，不用重启proxy，在新的主节点上启动心跳
				ProxyRuntime.INSTANCE.startHeartBeatScheduler();

				JoinCLusterNotifyPacket joinReps = createJoinNotifyPkg(session,JoinCLusterNotifyPacket.JOIN_STATE_NEED_ACK);
				notifyAllNodes(session, joinReps);
			} else {
				logger.warn("Leader crashed {} {}, enter Leader election state", myLeader.id, getMyNodeId());
				this.setClusterState(ClusterState.LeaderElection);

				// 当前集群失去主节点，关闭proxy服务
				ProxyStarter.INSTANCE.stopProxy();
			}
		} else if (myLeader == myNode) {
			if (checkIfNeedDismissCluster()) {
				logger.warn("Less than 1/2 numbers in my Kingdom, so I quit");
				this.setClusterState(ClusterState.LeaderElection);
				this.myLeader=null;
				JoinCLusterNotifyPacket joinReps = createJoinNotifyPkg(session, JoinCLusterNotifyPacket.JOIN_STATE_DENNIED);
				notifyAllNodes(session, joinReps);

				// 当前集群失去主节点，关闭proxy服务
				ProxyStarter.INSTANCE.stopProxy();
			}
		}
	}

	private boolean checkIfNeedDismissCluster() {
		int halfAllCount = allNodes.size() >> 1;
		int myAliveCount = getMyAliveNodesCount();
		if (nodesIsOdd()) {
			//奇数个节点，判断是否>一半
			return myAliveCount <= halfAllCount;
		} else {
			//偶数个节点，判断是否>一半 或者 =一半时主节点是集群中最小的节点
			return (myAliveCount < halfAllCount) || (myAliveCount == halfAllCount && !imSmallestInNodes());
		}
	}

	private boolean nodesIsOdd() {
		return (allNodes.size() & 1) == 1;
	}

	public String getMyNodeId() {
		return this.myNode.id;
	}

	public ClusterNode getMyLeader() {
		return myLeader;
	}

	public String getMyLeaderId() {
		return (myLeader == null) ? null : myLeader.id;
	}

	public ClusterNode findNode(String nodeId) {
		return allNodes.get(nodeId);
	}

	public ClusterNode getMyNode() {
		return myNode;
	}

	public void setMyLeader(ClusterNode myLeader) {
		this.myLeader = myLeader;
	}

	public ClusterState getClusterState() {
		return clusterState;
	}

	public long getLastClusterStateTime() {
		return lastClusterStateTime;
	}

	public void setClusterState(ClusterState clusterState) {
		this.clusterState = clusterState;
		this.lastClusterStateTime = System.currentTimeMillis();
	}
}
