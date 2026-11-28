"""PC-only device approval panel; intentionally has no remote admin endpoint."""
import argparse
import sys

# Windows build/test consoles may use a legacy code page; pythonw has no stdout.
for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")

import tkinter as tk
from tkinter import ttk, messagebox
from device_authorization import Registry, fingerprint

ROLE_NAMES = {"read": "只读", "operate": "运营", "manage": "管理"}
NAME_ROLES = {v:k for k,v in ROLE_NAMES.items()}
STATE_NAMES = {"pending":"等待授权", "approved":"已授权", "revoked":"已撤销"}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", default=None)
    args = parser.parse_args()
    registry = Registry(args.db)
    root = tk.Tk()
    root.title("团队工作相册 · 设备与权限（候选版）")
    root.geometry("820x520")
    root.minsize(640, 420)
    style = ttk.Style(root)
    style.theme_use("clam")
    style.configure("Treeview", rowheight=38, font=("Microsoft YaHei UI", 10))
    style.configure("TButton", padding=8)
    shell = ttk.Frame(root, padding=24)
    shell.pack(fill="both", expand=True)
    ttk.Label(shell, text="设备与权限", font=("Microsoft YaHei UI", 19, "bold")).pack(anchor="w")
    ttk.Label(shell, text="首次绑定须核对手机上的配对指纹。此候选面板仅管理本地授权记录。", wraplength=720).pack(anchor="w", pady=(6,16))
    table = ttk.Treeview(shell, columns=("name","fingerprint","state","role"), show="headings", selectmode="extended")
    for key,title,width in [("name","设备名称",180),("fingerprint","配对指纹",180),("state","状态",110),("role","权限",90)]:
        table.heading(key,text=title)
        table.column(key,width=width)
    table.pack(fill="both", expand=True)
    role = tk.StringVar(value="只读")
    bar = ttk.Frame(shell)
    bar.pack(fill="x", pady=14)
    ttk.Label(bar,text="权限").pack(side="left")
    ttk.Combobox(bar,textvariable=role, values=list(NAME_ROLES), state="readonly",width=9).pack(side="left",padx=8)
    status = tk.StringVar(value="")
    def refresh():
        selected = table.selection()
        table.delete(*table.get_children())
        for item in registry.list():
            table.insert("", "end", iid=item["id"], values=(item["name"],fingerprint(item["id"]),STATE_NAMES.get(item["state"],item["state"]),ROLE_NAMES.get(item["role"],"—")))
        remaining = [item for item in selected if table.exists(item)]
        if remaining:
            table.selection_set(remaining)
    def decide(state):
        selected=table.selection()
        if not selected:
            messagebox.showinfo("设备与权限","请先选择设备")
            return
        values = [table.item(item, "values") for item in selected]
        details = "\n".join("%s · %s"%(v[0],v[1]) for v in values)
        if state=="approved":
            prompt="确认以下每台手机的配对指纹均已核对，并授予「%s」权限？\n\n%s"%(role.get(),details)
        else:
            prompt="确认撤销以下 %d 台设备的授权？\n\n%s"%(len(selected),details)
        if not messagebox.askyesno("确认设备权限",prompt):
            return
        updated = sum(registry.decide(item,state,NAME_ROLES[role.get()] if state=="approved" else None) for item in selected)
        status.set("已更新 %d/%d 台设备；未成功的记录请刷新核查"%(updated,len(selected)))
        refresh()
    ttk.Button(bar,text="允许 / 修改权限",command=lambda:decide("approved")).pack(side="left",padx=5)
    ttk.Button(bar,text="拒绝 / 撤销",command=lambda:decide("revoked")).pack(side="left",padx=5)
    ttk.Button(bar,text="刷新",command=refresh).pack(side="right")
    def revoke_all():
        if not messagebox.askyesno("撤销全部授权","所有已授权设备将失去访问权限，待审批请求也会被拒绝。需要重新配对。确认？"):
            return
        count = registry.revoke_all()
        status.set("已撤销 %d 台设备"%count)
        refresh()
    ttk.Button(bar,text="撤销全部",command=revoke_all).pack(side="right",padx=5)
    ttk.Label(shell,textvariable=status).pack(anchor="w")
    ttk.Label(shell,text="只读：浏览和下载 · 运营：额外使用记录 · 管理：已审核编辑操作。永久删除不开放。",wraplength=720).pack(anchor="w",pady=(8,0))
    refresh()
    root.mainloop()

if __name__ == "__main__":
    main()
