import SwiftUI

struct TrashView: View {
    @EnvironmentObject private var library: WorkLibrary
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if library.trash.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "trash").font(.system(size: 48)).foregroundStyle(.secondary)
                        Text("回收站是空的").font(.title2.bold())
                        Text("已打开分享的作品会在下一天打开相册时移到这里，并保留 7 天。")
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(32)
                } else {
                    List(library.trash) { item in
                        HStack(spacing: 12) {
                            Image(systemName: "folder.fill")
                                .foregroundStyle(.secondary)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(item.name).font(.headline)
                                Text(detail(item)).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("恢复") { Task { await library.restore(item) } }
                                .buttonStyle(.bordered)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .navigationTitle("回收站")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private func detail(_ item: TrashItem) -> String {
        let count = "已打开分享 \(item.shareCount) 次"
        guard let date = item.trashedDate else { return count }
        return "\(count) · \(date.formatted(date: .abbreviated, time: .omitted))移入"
    }
}
