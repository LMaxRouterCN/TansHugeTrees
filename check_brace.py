# 括号匹配检查工具
def check_braces(file_path):
    depth = 0
    max_depth = 0
    line_num = 0
    with open(file_path, 'r', encoding='utf-8') as f:
        for line in f:
            line_num += 1
            depth += line.count('{') - line.count('}')
            max_depth = max(max_depth, depth)
            if depth < 0:
                print(f"第 {line_num} 行: 括号不匹配 (过多的 '}}')")
                break
    if depth > 0:
        print(f"文件结束时未闭合: 缺少 {depth} 个 '}}'，最大深度 {max_depth}")
    elif depth == 0:
        print(f"括号匹配正常，最大深度 {max_depth}")

if __name__ == '__main__':
    import sys
    check_braces('src/main/java/tannyjung/tanshugetrees_handcode/systems/world_gen/TreeLocation.java')