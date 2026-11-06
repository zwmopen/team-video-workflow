//
//  PullProgressViewController.swift
//  Album
//
//  DSH-202：iOS 拉原图进度面板 —— 与安卓端「正在从电脑同步原图到手机…」对齐。
//  过去 iOS 只有 UIAlertController 的一行文字（每秒换一句话），既看不到进度条，
//  也不知道到底拉到第几张；安卓那边一直是「标题 + 状态行 + 横向进度条 + 右侧计数」。
//

import UIKit

final class PullProgressViewController: UIViewController {

    /// 用户点了「取消」：只关面板，不打断已在飞的下载（与安卓一致）。
    var onCancel: (() -> Void)?

    private let totalImages: Int
    private var done = 0
    private var lastName = ""
    private var startedAt = Date()

    private let card = UIView()
    private let titleLabel = UILabel()
    private let statusLabel = UILabel()
    private let progressView = UIProgressView(progressViewStyle: .default)
    private let counterLabel = UILabel()
    private let cancelButton = UIButton(type: .system)

    private let accent = UIColor(red: 0.10, green: 0.50, blue: 0.98, alpha: 1.0)

    init(total: Int) {
        self.totalImages = max(1, total)
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .overFullScreen
        modalTransitionStyle = .crossDissolve
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.black.withAlphaComponent(0.45)
        buildUI()
        refresh()
    }

    // MARK: - UI

    private func buildUI() {
        card.translatesAutoresizingMaskIntoConstraints = false
        card.layer.cornerRadius = 16
        card.layer.masksToBounds = true

        let background: UIColor
        let primary: UIColor
        let secondary: UIColor
        if #available(iOS 13.0, *) {
            background = .secondarySystemBackground
            primary = .label
            secondary = .secondaryLabel
        } else {
            background = .white
            primary = .black
            secondary = .darkGray
        }
        card.backgroundColor = background

        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = "正在从电脑同步原图到手机…"
        titleLabel.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        titleLabel.textColor = primary
        titleLabel.numberOfLines = 2

        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.font = UIFont.systemFont(ofSize: 12, weight: .regular)
        statusLabel.textColor = secondary
        statusLabel.numberOfLines = 2
        statusLabel.lineBreakMode = .byTruncatingMiddle

        progressView.translatesAutoresizingMaskIntoConstraints = false
        progressView.progressTintColor = accent
        progressView.trackTintColor = accent.withAlphaComponent(0.18)
        progressView.progress = 0

        counterLabel.translatesAutoresizingMaskIntoConstraints = false
        counterLabel.font = UIFont.systemFont(ofSize: 12, weight: .semibold)
        counterLabel.textColor = accent
        counterLabel.textAlignment = .right

        cancelButton.translatesAutoresizingMaskIntoConstraints = false
        cancelButton.setTitle("取消", for: .normal)
        cancelButton.titleLabel?.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        cancelButton.setTitleColor(accent, for: .normal)
        cancelButton.addTarget(self, action: #selector(handleCancel), for: .touchUpInside)

        view.addSubview(card)
        card.addSubview(titleLabel)
        card.addSubview(statusLabel)
        card.addSubview(progressView)
        card.addSubview(counterLabel)
        card.addSubview(cancelButton)

        let cardWidth = min(320, UIScreen.main.bounds.width - 64)
        NSLayoutConstraint.activate([
            card.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            card.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            card.widthAnchor.constraint(equalToConstant: cardWidth),

            titleLabel.topAnchor.constraint(equalTo: card.topAnchor, constant: 20),
            titleLabel.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 20),
            titleLabel.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -20),

            statusLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 6),
            statusLabel.leadingAnchor.constraint(equalTo: titleLabel.leadingAnchor),
            statusLabel.trailingAnchor.constraint(equalTo: titleLabel.trailingAnchor),

            progressView.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 12),
            progressView.leadingAnchor.constraint(equalTo: titleLabel.leadingAnchor),
            progressView.trailingAnchor.constraint(equalTo: titleLabel.trailingAnchor),
            progressView.heightAnchor.constraint(equalToConstant: 6),

            counterLabel.topAnchor.constraint(equalTo: progressView.bottomAnchor, constant: 6),
            counterLabel.leadingAnchor.constraint(equalTo: titleLabel.leadingAnchor),
            counterLabel.trailingAnchor.constraint(equalTo: titleLabel.trailingAnchor),

            cancelButton.topAnchor.constraint(equalTo: counterLabel.bottomAnchor, constant: 8),
            cancelButton.trailingAnchor.constraint(equalTo: titleLabel.trailingAnchor),
            cancelButton.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -12),
            cancelButton.heightAnchor.constraint(equalToConstant: 32),
            cancelButton.widthAnchor.constraint(equalToConstant: 64),
        ])
    }

    // MARK: - 对外接口

    /// 每完成一张回调一次（计数 + 当前文件名）。
    func note(done: Int, total: Int, name: String) {
        self.done = max(0, min(done, totalImages))
        if !name.isEmpty { self.lastName = name }
        refresh()
    }

    /// 秒级心跳：没有新图落盘时也要让「已用时」和状态行保持活着（DSH-198 的教训）。
    func tick() {
        refresh()
    }

    // MARK: - 内部

    private var elapsedSeconds: Int {
        return Int(Date().timeIntervalSince(startedAt))
    }

    private func refresh() {
        let elapsed = elapsedSeconds
        if done <= 0 {
            statusLabel.text = "准备连接电脑拉取 \(totalImages) 张原图…"
            counterLabel.text = "0 / \(totalImages) 张 · \(elapsed) 秒"
        } else if done >= totalImages {
            statusLabel.text = "正在准备分享…"
            counterLabel.text = "\(done) / \(totalImages) 张 · 用时 \(elapsed) 秒"
        } else {
            statusLabel.text = lastName.isEmpty ? "正在下载…" : "正在下载：\(lastName)"
            counterLabel.text = "\(done) / \(totalImages) 张 · \(elapsed) 秒"
        }
        progressView.setProgress(Float(done) / Float(totalImages), animated: true)
    }

    @objc private func handleCancel() {
        onCancel?()
        dismiss(animated: true, completion: nil)
    }
}
