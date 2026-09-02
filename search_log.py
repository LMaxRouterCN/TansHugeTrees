# 临时调试脚本：搜索MC日志中的THT-DEBUG调试信息（用完可删）
import io

LOG_PATH = r"E:\MC\.minecraft\versions\TEST 1.20.1-Forge_47.4.10\logs\latest.log"
KEYWORD = "THT-DEBUG"
MAX_LINES = 40  # 只输出前40条，防止挤爆上下文

with io.open(LOG_PATH, encoding="utf-8", errors="replace") as f:
    matched = [l.rstrip() for l in f if KEYWORD in l]

for l in matched[:MAX_LINES]:
    print(l)
print(f"--- total matched: {len(matched)} ---")