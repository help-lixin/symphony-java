package help.lixin.symphony.config;

import java.util.List;

/**
 * Worker配置
 *
 * 功能说明:
 * - 定义SSH worker主机列表
 * - 支持本地和远程worker
 *
 * 配置项:
 * - sshHosts: SSH主机地址列表
 */
public class WorkerConfig {

    private List<String> sshHosts;

    public WorkerConfig() {
    }

    /**
     * 获取SSH主机列表
     *
     * @return SSH主机列表
     */
    public List<String> getSshHosts() {
        return sshHosts;
    }

    /**
     * 设置SSH主机列表
     *
     * @param sshHosts SSH主机列表
     */
    public void setSshHosts(List<String> sshHosts) {
        this.sshHosts = sshHosts;
    }
}
