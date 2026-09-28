# -*- coding: utf-8 -*-
"""
成品库 24 大货架 1:1 物理拆分与纯净化重组脚本
执行目标：
1. 从「综合与其它城市」中拆分出：
   - 江浙沪成品 (47套)
   - 乌镇成品 (3套)
   - 无锡成品 (2套)
   - 湖州成品 (2套)
   - 台州成品 (1套)
   - 金华成品 (1套)
   - 义乌成品 (1套)
   - 剩余 37 套继续保留在「综合与其它城市」
2. 将「中秋国庆成品」拆分规整出：
   - 中秋成品 (8套纯中秋)
   - 国庆成品 (7套纯国庆)
   - 中秋国庆成品 (1套双节复合方案)
3. 保证 100% 零文件丢失，整套作品原子移动，保留完整图片、文案与标签。
"""
import os
import shutil
import sys

PORTFOLIO_ROOT = r"D:\AICode\项目推进\projects\江湖有旅人\主项目\成品库（GPT+本地脚本制作）"

def main():
    if not os.path.isdir(PORTFOLIO_ROOT):
        print(f"错误：成品库根目录不存在: {PORTFOLIO_ROOT}")
        sys.exit(1)

    print("=== 开始执行 24 大成品货架 1:1 拆分创建 ===")

    # 1. 拆分「综合与其它城市」
    p_zonghe = os.path.join(PORTFOLIO_ROOT, "综合与其它城市")
    if os.path.isdir(p_zonghe):
        works = os.listdir(p_zonghe)
        print(f"当前「综合与其它城市」作品总数: {len(works)}")

        wuzhen = [w for w in works if '乌镇' in w]
        wuxi = [w for w in works if '无锡' in w]
        huzhou = [w for w in works if '湖州' in w]
        taizhou = [w for w in works if any(k in w for k in ('台州', '仙居', '临海'))]
        jinhua = [w for w in works if any(k in w for k in ('金华', '武义'))]
        yiwu = [w for w in works if '义乌' in w]
        jiangzhehu = [w for w in works if any(k in w for k in ('江浙沪', '华东', '长三角', '自驾', '徒步', '赏秋', '路线')) and w not in wuzhen + wuxi + huzhou + taizhou + jinhua + yiwu]

        moves = [
            ("江浙沪成品", jiangzhehu),
            ("乌镇成品", wuzhen),
            ("无锡成品", wuxi),
            ("湖州成品", huzhou),
            ("台州成品", taizhou),
            ("金华成品", jinhua),
            ("义乌成品", yiwu),
        ]

        for target_folder_name, work_list in moves:
            target_dir = os.path.join(PORTFOLIO_ROOT, target_folder_name)
            os.makedirs(target_dir, exist_ok=True)
            print(f"--> 创建并平移至「{target_folder_name}」: {len(work_list)} 套作品")
            for w in work_list:
                src = os.path.join(p_zonghe, w)
                dst = os.path.join(target_dir, w)
                if os.path.exists(src) and not os.path.exists(dst):
                    shutil.move(src, dst)
                elif os.path.exists(src) and os.path.exists(dst):
                    print(f"    目标已存在跳过: {w}")

    # 2. 拆分「中秋国庆成品」
    p_zqgq = os.path.join(PORTFOLIO_ROOT, "中秋国庆成品")
    if os.path.isdir(p_zqgq):
        zq_works = os.listdir(p_zqgq)
        print(f"\n当前「中秋国庆成品」作品总数: {len(zq_works)}")

        mid_autumn = [w for w in zq_works if '中秋' in w and '国庆' not in w and '十一' not in w]
        nat_day = [w for w in zq_works if ('国庆' in w or '十一' in w) and '中秋' not in w]

        target_zq = os.path.join(PORTFOLIO_ROOT, "中秋成品")
        target_gq = os.path.join(PORTFOLIO_ROOT, "国庆成品")
        os.makedirs(target_zq, exist_ok=True)
        os.makedirs(target_gq, exist_ok=True)

        print(f"--> 创建并平移至「中秋成品」: {len(mid_autumn)} 套纯中秋作品")
        for w in mid_autumn:
            src = os.path.join(p_zqgq, w)
            dst = os.path.join(target_zq, w)
            if os.path.exists(src) and not os.path.exists(dst):
                shutil.move(src, dst)

        print(f"--> 创建并平移至「国庆成品」: {len(nat_day)} 套纯国庆作品")
        for w in nat_day:
            src = os.path.join(p_zqgq, w)
            dst = os.path.join(target_gq, w)
            if os.path.exists(src) and not os.path.exists(dst):
                shutil.move(src, dst)

    print("\n=== 24 大货架物理拆分平移完成 ===")

if __name__ == "__main__":
    main()
