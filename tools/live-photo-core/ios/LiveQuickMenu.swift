import UIKit

/// Native anchored translucent menu shared by Album and LiveStudio.
/// Uses an animated sliding platter for continuous hold-drag-release selection.
final class LiveQuickMenu: NSObject {
    private weak var anchor: UIView?
    private var overlay: UIControl?
    private var panel: UIVisualEffectView?
    private var scroll: UIScrollView?
    private var hoverPlatter: UIView?
    private var rows: [UIButton] = []
    private var selection: ((Int) -> Void)?
    private var bgObserver: NSObjectProtocol?
    private var current = -1
    private var highlighted = -1
    private var start = CGPoint.zero
    private var moved = false

    deinit {
        if let obs = bgObserver { NotificationCenter.default.removeObserver(obs) }
    }

    var isOpen: Bool { overlay != nil }
    func dismiss() {
        if let obs = bgObserver {
            NotificationCenter.default.removeObserver(obs)
            bgObserver = nil
        }
        overlay?.removeFromSuperview()
        overlay = nil; panel = nil; scroll = nil; hoverPlatter = nil
        rows.removeAll(); selection = nil; highlighted = -1
    }

    func handle(_ gesture: UILongPressGestureRecognizer, labels: [String], selected: Int,
                onSelect: @escaping (Int) -> Void) {
        guard let target = gesture.view, let window = target.window else { dismiss(); return }
        switch gesture.state {
        case .began:
            start = gesture.location(in: window); moved = false
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            show(anchor: target, labels: labels, selected: selected, onSelect: onSelect)
        case .changed:
            let p = gesture.location(in: window)
            if hypot(p.x - start.x, p.y - start.y) > 8 { moved = true }
            if moved { update(p, in: window) }
        case .ended:
            if moved {
                update(gesture.location(in: window), in: window)
                if highlighted >= 0 { commit(highlighted) } else { dismiss() }
            }
        case .cancelled, .failed:
            dismiss()
        default:
            break
        }
    }

    func show(anchor target: UIView, labels: [String], selected: Int, onSelect: @escaping (Int) -> Void) {
        dismiss()
        guard let window = target.window, !labels.isEmpty else { return }
        anchor = target; current = selected; selection = onSelect
        bgObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in self?.dismiss() }

        let cover = UIControl(frame: window.bounds)
        cover.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        cover.backgroundColor = UIColor.black.withAlphaComponent(0.035)
        cover.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        cover.accessibilityViewIsModal = true

        let safe: CGRect
        if #available(iOS 11.0, *) {
            safe = window.bounds.inset(by: window.safeAreaInsets).insetBy(dx: 8, dy: 8)
        } else {
            safe = window.bounds.insetBy(dx: 8, dy: 24)
        }
        let rect = target.convert(target.bounds, to: window)
        let below = rect.maxY + 6, above = rect.minY - 6
        let width = min(228, safe.width)
        guard safe.width > 80 else { selection = nil; return }

        let font = UIFont.preferredFont(forTextStyle: .subheadline)
        let textMaxW = max(60, width - 34)
        var rowHeights: [CGFloat] = []
        for label in labels {
            let text = label + "  ✓"
            let measured = (text as NSString).boundingRect(
                with: CGSize(width: textMaxW, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                attributes: [.font: font],
                context: nil
            ).height
            rowHeights.append(max(38, ceil(measured) + 12))
        }
        let desired = rowHeights.reduce(12, +)
        let down = safe.maxY - below >= min(desired, above - safe.minY)
        let available = down ? (safe.maxY - below) : (above - safe.minY)
        guard available >= 84 else { selection = nil; return }
        let height = min(desired, min(available, 276))
        let x = max(safe.minX, min(safe.maxX - width, rect.maxX - width))
        let y = down ? below : (above - height)

        let blurStyle: UIBlurEffect.Style
        let separatorColor: UIColor
        let labelColor: UIColor
        let selectedRowColor: UIColor
        if #available(iOS 13.0, *) {
            let isDark = window.traitCollection.userInterfaceStyle == .dark
            blurStyle = .systemThickMaterial
            separatorColor = UIColor.separator
            labelColor = UIColor.label
            selectedRowColor = UIColor.tertiarySystemFill
            _ = isDark
        } else {
            blurStyle = .extraLight
            separatorColor = UIColor(white: 0.78, alpha: 0.5)
            labelColor = .darkText
            selectedRowColor = UIColor(white: 0.5, alpha: 0.14)
        }

        let glass = UIVisualEffectView(effect: UIBlurEffect(style: blurStyle))
        glass.frame = CGRect(x: x, y: y, width: width, height: height)
        glass.layer.cornerRadius = 22
        glass.layer.borderWidth = 0.5
        glass.layer.borderColor = separatorColor.cgColor
        glass.clipsToBounds = true

        let sc = UIScrollView(frame: glass.bounds)
        sc.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        sc.showsVerticalScrollIndicator = false
        sc.contentSize = CGSize(width: width, height: desired)

        let platter = UIView()
        platter.backgroundColor = UIColor.systemBlue.withAlphaComponent(0.17)
        platter.layer.cornerRadius = 13
        platter.isUserInteractionEnabled = false
        platter.alpha = 0
        sc.addSubview(platter)
        hoverPlatter = platter

        var curY: CGFloat = 6
        for (i, label) in labels.enumerated() {
            let rh = rowHeights[i]
            let row = UIButton(type: .system)
            row.frame = CGRect(x: 6, y: curY, width: width - 12, height: rh)
            curY += rh
            row.tag = i
            row.setTitle(label + (i == selected ? "  ✓" : ""), for: .normal)
            row.titleLabel?.font = font
            row.titleLabel?.numberOfLines = 2
            row.setTitleColor(labelColor, for: .normal)
            row.contentHorizontalAlignment = .left
            row.contentEdgeInsets = UIEdgeInsets(top: 5, left: 12, bottom: 5, right: 10)
            row.layer.cornerRadius = 13
            row.backgroundColor = (i == selected) ? selectedRowColor : .clear
            row.accessibilityLabel = label
            if i == selected { row.accessibilityTraits.insert(.selected) }
            row.addTarget(self, action: #selector(rowTapped(_:)), for: .touchUpInside)
            sc.addSubview(row)
            rows.append(row)
        }
        glass.contentView.addSubview(sc)
        cover.addSubview(glass)
        window.addSubview(cover)
        overlay = cover; panel = glass; scroll = sc

        if selected >= 0 && selected < rows.count {
            let rowFrame = rows[selected].frame
            if rowFrame.maxY > height - 8 {
                sc.contentOffset.y = min(max(0, desired - height), max(0, rowFrame.minY - 36))
            }
        }
        if !UIAccessibility.isReduceMotionEnabled {
            glass.alpha = 0; glass.transform = CGAffineTransform(scaleX: 0.94, y: 0.94)
            UIView.animate(withDuration: 0.22, delay: 0, usingSpringWithDamping: 0.87, initialSpringVelocity: 0.3, options: [.allowUserInteraction]) {
                glass.alpha = 1; glass.transform = .identity
            }
        }
        UIAccessibility.post(notification: .layoutChanged, argument: rows.first)
    }

    private func paint(_ hit: Int) {
        guard let platter = hoverPlatter else { return }
        if hit < 0 || hit >= rows.count {
            if !UIAccessibility.isReduceMotionEnabled && platter.alpha > 0 {
                UIView.animate(withDuration: 0.12) { platter.alpha = 0 }
            } else {
                platter.alpha = 0
            }
            return
        }
        let targetFrame = rows[hit].frame
        let wasHidden = platter.alpha < 0.05
        if wasHidden {
            platter.frame = targetFrame
            if !UIAccessibility.isReduceMotionEnabled {
                UIView.animate(withDuration: 0.12) { platter.alpha = 1 }
            } else {
                platter.alpha = 1
            }
        } else if !UIAccessibility.isReduceMotionEnabled {
            UIView.animate(withDuration: 0.16, delay: 0, usingSpringWithDamping: 0.86, initialSpringVelocity: 0.2, options: [.beginFromCurrentState, .allowUserInteraction]) {
                platter.frame = targetFrame
                platter.alpha = 1
            }
        } else {
            platter.frame = targetFrame
            platter.alpha = 1
        }
    }

    private func update(_ p: CGPoint, in window: UIWindow) {
        guard let glass = panel, let sc = scroll else { return }
        let q = glass.convert(p, from: window)
        var hit = -1
        if glass.bounds.contains(q) {
            if sc.contentSize.height > sc.bounds.height {
                if q.y > glass.bounds.height - 26 {
                    sc.contentOffset.y = min(sc.contentSize.height - sc.bounds.height, sc.contentOffset.y + 12)
                } else if q.y < 26 {
                    sc.contentOffset.y = max(0, sc.contentOffset.y - 12)
                }
            }
            for (i, row) in rows.enumerated() where row.bounds.contains(row.convert(p, from: window)) {
                hit = i; break
            }
        }
        if hit != highlighted {
            highlighted = hit
            paint(hit)
            if hit >= 0 { UISelectionFeedbackGenerator().selectionChanged() }
        }
    }

    private func commit(_ n: Int) {
        let callback = selection
        dismiss()
        callback?(n)
    }

    @objc private func cancelTapped() { dismiss() }
    @objc private func rowTapped(_ sender: UIButton) { commit(sender.tag) }
}
