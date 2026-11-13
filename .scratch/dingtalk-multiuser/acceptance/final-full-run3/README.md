# 最终完整门禁直接收据

审源c20d1182f71ec3dc87512cc86d030d360385681c。主控实际host退出0，source前后一致，1071个受检Git对象匹配。此处8份退出/源/摘要元数据及原始日志校验表均从logs/final-full-run3逐字节复制，9/9 SHA256一致。

test-summary.txt直接提取本轮日志的最终计数及指定验收类结果，不是另一次测试；skipped-tests.json来自本轮Surefire XML。source-paths/hashes-before是与审源绑定的清单，完整after清单保留在原始日志目录，source-stability记录二者相同。

raw-log-SHA256SUMS.txt适用于../../logs/final-full-run3的22份原始文件（该目录被gitignore且本地保留），不是对本文件夹作校验。receipt-SHA256SUMS.txt校验本文件夹内其余10份收据/说明。未收录包含请求上下文的原始大日志；未推送或部署。
