# 最终完整门禁直接收据

审源c20d1182f71ec3dc87512cc86d030d360385681c。主控实际host退出0，source前后一致，1071个受检Git对象匹配。此处8份退出/源/摘要元数据及原始日志校验表均从logs/final-full-run3逐字节复制，9/9 SHA256一致。

test-summary.txt直接提取本轮日志的最终计数及指定验收类结果，不是另一次测试；skipped-tests.json来自本轮Surefire XML。source-paths/hashes-before是与审源绑定的清单，完整after清单保留在原始日志目录，source-stability记录二者相同。

raw-log-SHA256SUMS.txt适用于../../logs/final-full-run3的22份原始文件（该目录被gitignore且本地保留），不是对本文件夹作校验。receipt-SHA256SUMS.txt校验列出的10份收据/说明。未收录包含请求上下文的原始大日志；未推送或部署。

收据目录的.gitattributes禁用文本换行转换，确保Git对象与跨平台检出均保留原始字节；该属性文件不属于运行收据。归档时曾发现默认归一化使5份Git对象与工作区校验值不同，已按原始文件重新入库并逐份核验，不改应用或测试源码。
