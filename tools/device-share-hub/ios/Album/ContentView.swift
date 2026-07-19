import SwiftUI

private struct SharePayload: Identifiable {
    let id = UUID()
    let items: [Any]
}

struct ContentView: View {
    @EnvironmentObject private var library: WorkLibrary
    @State private var showFolderPicker = false
    @State private var showSettings = false
    @State private var showTrash = false
    @State private var sharePayload: SharePayload?

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        NavigationStack {
            Group {
                if library.folderName == nil {
                    EmptyLibraryView { showFolderPicker = true }
                } else if library.works.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "photo.on.rectangle.angled")
                            .font(.system(size: 48))
                            .foregroundStyle(.secondary)
                        Text("没有找到作品").font(.title2.bold())
                        Text(library.scanSummary ?? "请把同时含有图片和 TXT 的作品文件夹放进“\(library.folderName ?? "作品总文件夹")”。")
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                        Button("重新选择作品总文件夹") { showFolderPicker = true }
                            .buttonStyle(.borderedProminent)
                        Button("重新扫描") { Task { await library.refresh() } }
                            .buttonStyle(.bordered)
                    }
                    .padding(32)
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 12) {
                            ForEach(library.works) { work in
                                WorkCard(work: work) { openShare(work) }
                            }
                        }
                        .padding(16)
                    }
                    .refreshable { await library.refresh() }
                }
            }
            .navigationTitle("作品")
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button { showTrash = true } label: {
                        Image(systemName: library.trash.isEmpty ? "trash" : "trash.fill")
                    }
                    .accessibilityLabel("回收站，\(library.trash.count) 项")

                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("设置")
                }
            }
            .overlay(alignment: .top) {
                if let message = library.message {
                    Text(message)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.black.opacity(0.82), in: Capsule())
                        .padding(.top, 8)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .task {
                            try? await Task.sleep(nanoseconds: 2_200_000_000)
                            withAnimation { library.message = nil }
                        }
                }
            }
            .overlay {
                if library.isBusy {
                    ProgressView("正在扫描…")
                        .padding(18)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                }
            }
        }
        .sheet(isPresented: $showFolderPicker) {
            FolderPicker(
                onPick: { url in
                    showFolderPicker = false
                    Task { await library.selectFolder(url) }
                },
                onCancel: { showFolderPicker = false }
            )
        }
        .sheet(isPresented: $showSettings) {
            SettingsView(showFolderPicker: $showFolderPicker)
                .environmentObject(library)
        }
        .sheet(isPresented: $showTrash) {
            TrashView().environmentObject(library)
        }
        .sheet(item: $sharePayload) { payload in
            ActivitySheet(items: payload.items)
        }
        .alert("操作没有完成", isPresented: Binding(
            get: { library.errorMessage != nil },
            set: { if !$0 { library.errorMessage = nil } }
        )) {
            Button("知道了", role: .cancel) { library.errorMessage = nil }
        } message: {
            Text(library.errorMessage ?? "请重试。")
        }
    }

    private func openShare(_ work: WorkItem) {
        do {
            sharePayload = SharePayload(items: try library.prepareShare(work))
        } catch {
            library.errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }
}

private struct EmptyLibraryView: View {
    let choose: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "photo.stack")
                .font(.system(size: 58, weight: .medium))
                .foregroundStyle(.tint)
            VStack(spacing: 8) {
                Text("选择作品总文件夹")
                    .font(.title2.bold())
                Text("以后只需打开相册、点一个作品。文案会自动复制，全部图片会进入系统分享面板。")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            Button("选择文件夹", action: choose)
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
        }
        .padding(32)
    }
}

private struct WorkCard: View {
    let work: WorkItem
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Image(systemName: work.shareCount > 0 ? "checkmark.circle.fill" : "photo.on.rectangle")
                        .font(.title2)
                    Spacer()
                    Text("\(work.imageURLs.count) 图")
                        .font(.caption.weight(.medium))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(.thinMaterial, in: Capsule())
                }
                Spacer(minLength: 4)
                Text(work.name)
                    .font(.headline)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Text(work.shareCount == 0 ? "点一下复制并分享" : "已打开分享 \(work.shareCount) 次")
                    .font(.caption)
                    .foregroundStyle(work.shareCount == 0 ? .secondary : .primary)
            }
            .frame(maxWidth: .infinity, minHeight: 126, alignment: .leading)
            .padding(14)
            .foregroundStyle(work.shareCount > 0 ? .secondary : .primary)
            .background(
                work.shareCount > 0 ? Color(uiColor: .secondarySystemFill) : Color(uiColor: .secondarySystemBackground),
                in: RoundedRectangle(cornerRadius: 18, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(work.shareCount > 0 ? Color.secondary.opacity(0.16) : Color.accentColor.opacity(0.18))
            }
        }
        .buttonStyle(.plain)
        .accessibilityHint("复制文案并打开系统分享面板")
    }
}
