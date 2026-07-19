import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var library: WorkLibrary
    @Environment(\.dismiss) private var dismiss
    @Binding var showFolderPicker: Bool

    var body: some View {
        NavigationStack {
            List {
                Section("作品文件夹") {
                    LabeledContent("当前文件夹", value: library.folderName ?? "未选择")
                    Button("重新选择文件夹") {
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { showFolderPicker = true }
                    }
                    Button("重新扫描") { Task { await library.refresh() } }
                }

                Section("工作方式") {
                    Label("点击作品后自动复制 TXT 文案", systemImage: "doc.on.clipboard")
                    Label("所有图片进入 iOS 系统分享面板", systemImage: "square.and.arrow.up")
                    Label("次日打开时移入回收站，保留 7 天", systemImage: "trash")
                }

                Section("隐私") {
                    Text("素材和状态只保存在你授权的文件夹，不上传服务器，不修改图片拍摄信息，也不冒充系统照片来源。")
                        .foregroundStyle(.secondary)
                }

                Section("软件") {
                    LabeledContent("名称", value: "相册")
                    LabeledContent("版本", value: appVersion)
                    Text("AltStore 自用版 · 实体 iPhone 验收前均为“已实现未验收”")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("设置")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
    }
}

