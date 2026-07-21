package help.lixin.symphony.dashboard;

/**
 * StatusDashboard 协议
 *
 * 定义 StatusDashboard 与 Orchestrator 之间的通信消息类型
 */
public class StatusDashboardProtocol {

    private StatusDashboardProtocol() {
        // 协议类不实例化
    }

    /**
     * Dashboard 更新消息
     * 用于通知 Dashboard 需要刷新状态
     */
    public static class Update {
        public static final Update INSTANCE = new Update();
        private Update() {}
    }
}
