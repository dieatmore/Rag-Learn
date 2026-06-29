"""
PDF 推免规则表格粗提取脚本
============================
用途：从 PDF 中提取所有文字内容（文本 + 表格），输出为 .txt 和 .json，
      供后续人工校验层级关系使用。

为什么不用 Spring AI 的 PagePdfDocumentReader？
  - 该 PDF 含大量纵向合并单元格（"学术专长"跨20+行）
  - 第3页列结构从7列变为5列
  - Apache PdfBox 无法正确保留表格层级关系

依赖：pip install pdfplumber
用法：python scripts/extract_pdf.py
"""

import pdfplumber, json, os, sys

PDF_PATH = r"C:\Users\86155\Desktop\迪\推免项目\附表：信息学院推荐免试攻读研究生全面发展成绩指标.pdf"
OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "data")


def extract_text(pdf_path: str) -> str:
    """按页提取纯文本（保留换行，适合快速浏览）"""
    lines = []
    with pdfplumber.open(pdf_path) as pdf:
        for i, page in enumerate(pdf.pages):
            text = page.extract_text()
            lines.append(f"\n===== 第{i+1}页 =====")
            if text:
                lines.append(text)
    return "\n".join(lines)


def extract_table_raw(pdf_path: str) -> list[dict]:
    """提取表格的原始行列数据（每格文本 + 位置坐标）"""
    pages_data = []
    with pdfplumber.open(pdf_path) as pdf:
        for page_num, page in enumerate(pdf.pages):
            tables = page.find_tables()
            if not tables:
                continue

            for t_idx, table in enumerate(tables):
                raw = table.extract()  # 文本矩阵

                # 同时获取每个 cell 的 bbox，用于判断合并单元格
                rows_with_bbox = []
                for row_idx, (row_obj, ext_row) in enumerate(
                    zip(table.rows, raw)
                ):
                    cells = []
                    row_cells = row_obj.cells  # Row 对象的 cells 属性
                    for col_idx in range(len(ext_row)):
                        # row.cells[col_idx] 为 None 表示被上方合并
                        cell_bbox = (
                            row_cells[col_idx]
                            if col_idx < len(row_cells)
                            else None
                        )
                        cell_text = (
                            ext_row[col_idx].strip().replace("\n", " ")
                            if ext_row[col_idx]
                            else ""
                        )
                        cells.append({
                            "col": col_idx,
                            "is_merged": cell_bbox is None,
                            "text": cell_text,
                        })
                    rows_with_bbox.append(cells)

                pages_data.append({
                    "page": page_num + 1,
                    "table_index": t_idx,
                    "rows": rows_with_bbox,
                })
    return pages_data


def extract_with_layout(pdf_path: str) -> list[dict]:
    """
    布局模式提取：不依赖 table.find_tables()，
    直接提取页面中所有文字 + 位置，按 y 坐标排序。
    这是兜底方案——当表格自动检测失败时，至少能拿到所有文字。
    """
    pages = []
    with pdfplumber.open(pdf_path) as pdf:
        for page_num, page in enumerate(pdf.pages):
            chars = page.extract_words(
                keep_blank_chars=True,
                x_tolerance=3,
                y_tolerance=3,
            )
            # 按 y 坐标分组（同一行的文字）
            rows = {}
            for char in chars:
                y_key = round(char["top"], 1)
                if y_key not in rows:
                    rows[y_key] = {"y": y_key, "x_min": char["x0"], "words": []}
                rows[y_key]["words"].append({
                    "x0": char["x0"],
                    "text": char["text"],
                })
                rows[y_key]["x_min"] = min(rows[y_key]["x_min"], char["x0"])

            # 按 y 排序
            sorted_rows = sorted(rows.values(), key=lambda r: r["y"])
            lines = []
            for r in sorted_rows:
                # 同行文字按 x 排序
                sorted_words = sorted(r["words"], key=lambda w: w["x0"])
                line_text = " ".join(w["text"] for w in sorted_words)
                lines.append({
                    "y": r["y"],
                    "x_min": r["x_min"],
                    "text": line_text,
                })
            pages.append({"page": page_num + 1, "lines": lines})
    return pages


# ========== 执行 ==========
if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)

    # 1. 纯文本提取
    text = extract_text(PDF_PATH)
    text_path = os.path.join(OUT_DIR, "pdf_extract_text.txt")
    with open(text_path, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"[1/3] 纯文本 → {text_path}")

    # 2. 表格模式提取（带合并单元格标记）
    table_data = extract_table_raw(PDF_PATH)
    table_path = os.path.join(OUT_DIR, "pdf_extract_table.json")
    with open(table_path, "w", encoding="utf-8") as f:
        json.dump(table_data, f, ensure_ascii=False, indent=2)
    total_rows = sum(len(t["rows"]) for t in table_data)
    print(f"[2/3] 表格模式 → {table_path} ({total_rows} 行)")

    # 3. 布局模式提取（兜底）
    layout_data = extract_with_layout(PDF_PATH)
    layout_path = os.path.join(OUT_DIR, "pdf_extract_layout.json")
    with open(layout_path, "w", encoding="utf-8") as f:
        json.dump(layout_data, f, ensure_ascii=False, indent=2)
    total_lines = sum(len(p["lines"]) for p in layout_data)
    print(f"[3/3] 布局模式 → {layout_path} ({total_lines} 行)")

    print("\n✅ 粗提取完成。下一步：对照原始 PDF 校验层级关系。")
    print(f"   所有产出文件在: {OUT_DIR}")
