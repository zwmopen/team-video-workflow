import UIKit

/// FlClash-inspired elliptical translucent glass bar supporting tap and continuous finger scrub (iOS 12.0+).
public final class SlidingGlassBar: UIView {

    public struct Segment {
        public let title: String
        public let accessibilityLabel: String
        public let image: UIImage?
        public let isAccent: Bool
        public let isDestructive: Bool

        public init(
            title: String,
            accessibilityLabel: String? = nil,
            image: UIImage? = nil,
            isAccent: Bool = false,
            isDestructive: Bool = false
        ) {
            self.title = title
            self.accessibilityLabel = accessibilityLabel ?? title
            self.image = image
            self.isAccent = isAccent
            self.isDestructive = isDestructive
        }
    }

    public var onSelect: ((_ index: Int, _ fromScrub: Bool) -> Void)?
    public var onLongPress: ((_ index: Int) -> Bool)?

    private let dockMode: Bool
    private var segments: [Segment] = []
    private var enabledFlags: [Bool] = []
    private var darkMode: Bool = false
    private var glassStyle: Bool = true
    private var accentColor: UIColor = UIColor(red: 15/255, green: 135/255, blue: 88/255, alpha: 1)

    public private(set) var selectedIndex: Int = 0
    private var hoverIndex: Int = -1
    private var showHoverPill: Bool = false
    private var pillPosition: CGFloat = 0

    private let backgroundBlurView: UIVisualEffectView?
    private let fallbackTrackView = UIView()
    private let pillView = UIView()
    private let stackView = UIStackView()
    private var itemButtons: [UIButton] = []

    private var tracking = false
    private var scrubbing = false
    private var cancelledByDragOut = false
    private var longPressFired = false
    private var downPoint: CGPoint = .zero
    private var lastHapticIdx: Int = -1
    private var longPressWorkItem: DispatchWorkItem?
    private var selectionFeedback: Any?

    public init(dockMode: Bool) {
        self.dockMode = dockMode
        if #available(iOS 13.0, *) {
            let blur = UIVisualEffectView(effect: UIBlurEffect(style: .systemThinMaterial))
            blur.isUserInteractionEnabled = false
            self.backgroundBlurView = blur
        } else {
            self.backgroundBlurView = nil
        }
        super.init(frame: .zero)
        isMultipleTouchEnabled = false
        clipsToBounds = false

        fallbackTrackView.isUserInteractionEnabled = false
        addSubview(fallbackTrackView)
        if let blur = backgroundBlurView {
            blur.clipsToBounds = true
            addSubview(blur)
        }

        pillView.isUserInteractionEnabled = false
        addSubview(pillView)

        stackView.axis = .horizontal
        stackView.distribution = .fillEqually
        stackView.alignment = .fill
        stackView.spacing = 0
        stackView.isUserInteractionEnabled = false
        addSubview(stackView)

        if #available(iOS 10.0, *) {
            selectionFeedback = UISelectionFeedbackGenerator()
        }
        applyChrome()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    public func setSegments(_ items: [Segment], selected: Int = 0) {
        segments = items
        enabledFlags = Array(repeating: true, count: items.count)
        for view in itemButtons {
            stackView.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        itemButtons.removeAll()

        for (i, seg) in items.enumerated() {
            let btn = UIButton(type: .custom)
            btn.isUserInteractionEnabled = false
            btn.tag = i
            btn.accessibilityLabel = seg.accessibilityLabel
            btn.titleLabel?.font = UIFont.systemFont(ofSize: dockMode ? 12.5 : 12, weight: .medium)
            btn.titleLabel?.lineBreakMode = .byTruncatingTail
            btn.setTitle(seg.title.isEmpty ? nil : seg.title, for: .normal)
            if let img = seg.image {
                btn.setImage(img.withRenderingMode(.alwaysTemplate), for: .normal)
                btn.imageView?.contentMode = .scaleAspectFit
                if !seg.title.isEmpty {
                    btn.imageEdgeInsets = UIEdgeInsets(top: 0, left: -3, bottom: 0, right: 3)
                    btn.titleEdgeInsets = UIEdgeInsets(top: 0, left: 3, bottom: 0, right: -3)
                }
            }
            stackView.addArrangedSubview(btn)
            itemButtons.append(btn)
        }
        selectedIndex = clampIndex(selected)
        pillPosition = CGFloat(selectedIndex)
        applyChrome()
        setNeedsLayout()
    }

    public func setSelectedIndex(_ index: Int, animated: Bool = true) {
        selectedIndex = clampIndex(index)
        pillPosition = CGFloat(selectedIndex)
        if !dockMode {
            animatePill(animated: animated)
        }
        applyChrome()
    }

    public func setSegmentEnabled(_ index: Int, enabled: Bool) {
        guard index >= 0, index < enabledFlags.count else { return }
        enabledFlags[index] = enabled
        applyChrome()
    }

    public func segmentFrame(at index: Int, in targetView: UIView) -> CGRect {
        guard index >= 0, index < itemButtons.count else { return convert(bounds, to: targetView) }
        return itemButtons[index].convert(itemButtons[index].bounds, to: targetView)
    }

    public func applyAppearance(darkMode: Bool, glassStyle: Bool, accentColor: UIColor) {
        self.darkMode = darkMode
        self.glassStyle = glassStyle
        self.accentColor = accentColor
        applyChrome()
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        let inset: CGFloat = dockMode ? 5 : 3
        let radius = max(18, bounds.height / 2)
        fallbackTrackView.frame = bounds
        fallbackTrackView.layer.cornerRadius = radius
        if let blur = backgroundBlurView {
            blur.frame = bounds
            blur.layer.cornerRadius = radius
        }
        stackView.frame = bounds.insetBy(dx: inset, dy: inset)
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = dockMode ? (darkMode ? 0.38 : 0.14) : (darkMode ? 0.16 : 0.06)
        layer.shadowOffset = CGSize(width: 0, height: dockMode ? 6 : 2)
        layer.shadowRadius = dockMode ? 14 : 4
        layoutPillFrame()
    }

    // MARK: - Touch Scrubbing & Tap

    public override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard !segments.isEmpty, let pt = touches.first?.location(in: self) else { return }
        tracking = true
        scrubbing = false
        cancelledByDragOut = false
        longPressFired = false
        downPoint = pt
        lastHapticIdx = -1

        if #available(iOS 10.0, *), let gen = selectionFeedback as? UISelectionFeedbackGenerator {
            gen.prepare()
        }

        let idx = indexAt(x: pt.x)
        if dockMode {
            hoverIndex = isEnabled(idx) ? idx : -1
            showHoverPill = hoverIndex >= 0
            if showHoverPill {
                pillPosition = CGFloat(hoverIndex)
                scalePill(active: true)
            }
            layoutPillFrame()
            applyChrome()
        } else if onLongPress != nil {
            let work = DispatchWorkItem { [weak self] in
                guard let self = self, self.tracking, !self.scrubbing else { return }
                let pressIdx = self.indexAt(x: self.downPoint.x)
                if pressIdx >= 0, pressIdx < self.segments.count, self.isEnabled(pressIdx),
                   self.onLongPress?(pressIdx) == true {
                    self.longPressFired = true
                    if #available(iOS 10.0, *) {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    }
                }
            }
            longPressWorkItem = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.36, execute: work)
        }
    }

    public override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard tracking, let pt = touches.first?.location(in: self) else { return }
        let dx = pt.x - downPoint.x
        let dy = pt.y - downPoint.y

        if !scrubbing && abs(dx) > 6 && abs(dx) >= abs(dy) * 0.7 {
            scrubbing = true
            longPressWorkItem?.cancel()
            scalePill(active: true)
        }

        if dockMode {
            let margin: CGFloat = 38
            if pt.y < -margin || pt.y > bounds.height + margin {
                cancelledByDragOut = true
                showHoverPill = false
                hoverIndex = -1
                layoutPillFrame()
                applyChrome()
                return
            }
            cancelledByDragOut = false
        }

        if scrubbing {
            let continuous = continuousIndex(x: pt.x)
            let nearest = clampIndex(Int(round(continuous)))
            if dockMode {
                hoverIndex = isEnabled(nearest) ? nearest : -1
                showHoverPill = hoverIndex >= 0
            } else {
                selectedIndex = nearest
            }
            pillPosition = continuous
            layoutPillFrame()
            applyChrome()
            if nearest != lastHapticIdx, isEnabled(nearest) {
                lastHapticIdx = nearest
                if #available(iOS 10.0, *), let gen = selectionFeedback as? UISelectionFeedbackGenerator {
                    gen.selectionChanged()
                    gen.prepare()
                }
            }
        }
    }

    public override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        longPressWorkItem?.cancel()
        guard tracking else { return }
        tracking = false
        scalePill(active: false)

        let pt = touches.first?.location(in: self) ?? downPoint
        let targetIdx = indexAt(x: pt.x)
        let wasScrub = scrubbing
        let wasLong = longPressFired
        let wasCancelled = cancelledByDragOut
        scrubbing = false
        cancelledByDragOut = false

        if dockMode {
            showHoverPill = false
            hoverIndex = -1
            layoutPillFrame()
            applyChrome()
            if !wasCancelled && !wasLong && targetIdx >= 0 && isEnabled(targetIdx) {
                if #available(iOS 10.0, *) {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                }
                onSelect?(targetIdx, wasScrub)
            }
        } else {
            if !wasLong && targetIdx >= 0 && isEnabled(targetIdx) {
                selectedIndex = targetIdx
                pillPosition = CGFloat(targetIdx)
                animatePill(animated: true)
                applyChrome()
                onSelect?(targetIdx, wasScrub)
            } else {
                pillPosition = CGFloat(selectedIndex)
                animatePill(animated: true)
                applyChrome()
            }
        }
    }

    public override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        longPressWorkItem?.cancel()
        tracking = false
        scrubbing = false
        cancelledByDragOut = false
        scalePill(active: false)
        if dockMode {
            showHoverPill = false
            hoverIndex = -1
            layoutPillFrame()
        } else {
            pillPosition = CGFloat(selectedIndex)
            animatePill(animated: true)
        }
        applyChrome()
    }

    // MARK: - Helpers

    private func layoutPillFrame() {
        guard !segments.isEmpty, bounds.width > 8 else {
            pillView.isHidden = true
            return
        }
        if dockMode && !showHoverPill {
            pillView.isHidden = true
            return
        }
        pillView.isHidden = false
        let inset: CGFloat = dockMode ? 5 : 3
        let trackRect = bounds.insetBy(dx: inset, dy: inset)
        let segW = trackRect.width / CGFloat(segments.count)
        let clampedPos = max(0, min(CGFloat(segments.count - 1), pillPosition))
        let x = trackRect.minX + clampedPos * segW
        pillView.frame = CGRect(x: x, y: trackRect.minY, width: segW, height: trackRect.height)
        pillView.layer.cornerRadius = max(14, trackRect.height / 2)
    }

    private func animatePill(animated: Bool) {
        if !animated || UIAccessibility.isReduceMotionEnabled {
            layoutPillFrame()
            return
        }
        UIView.animate(
            withDuration: 0.24,
            delay: 0,
            usingSpringWithDamping: 0.84,
            initialSpringVelocity: 0.35,
            options: [.allowUserInteraction, .beginFromCurrentState],
            animations: { self.layoutPillFrame() }
        )
    }

    private func scalePill(active: Bool) {
        guard !UIAccessibility.isReduceMotionEnabled else { return }
        UIView.animate(withDuration: 0.14, delay: 0, options: [.allowUserInteraction]) {
            self.pillView.transform = active ? CGAffineTransform(scaleX: 1.05, y: 1.06) : .identity
        }
    }

    private func applyChrome() {
        if let blur = backgroundBlurView {
            blur.isHidden = !glassStyle
            if #available(iOS 13.0, *) {
                blur.effect = UIBlurEffect(style: darkMode ? .systemThinMaterialDark : .systemThinMaterialLight)
            }
        }
        if glassStyle {
            if dockMode {
                fallbackTrackView.backgroundColor = darkMode
                    ? UIColor(red: 34/255, green: 38/255, blue: 48/255, alpha: 0.82)
                    : UIColor(white: 1.0, alpha: 0.88)
                fallbackTrackView.layer.borderColor = darkMode
                    ? UIColor(white: 1.0, alpha: 0.22).cgColor
                    : UIColor(white: 0.0, alpha: 0.14).cgColor
            } else {
                fallbackTrackView.backgroundColor = darkMode
                    ? UIColor(red: 30/255, green: 34/255, blue: 42/255, alpha: 0.72)
                    : UIColor(red: 236/255, green: 239/255, blue: 243/255, alpha: 0.82)
                fallbackTrackView.layer.borderColor = darkMode
                    ? UIColor(white: 1.0, alpha: 0.15).cgColor
                    : UIColor(white: 0.0, alpha: 0.10).cgColor
            }
        } else {
            fallbackTrackView.backgroundColor = darkMode
                ? UIColor(red: 35/255, green: 37/255, blue: 41/255, alpha: 1.0)
                : (dockMode ? .white : UIColor(red: 234/255, green: 236/255, blue: 239/255, alpha: 1.0))
            fallbackTrackView.layer.borderColor = darkMode
                ? UIColor(white: 1.0, alpha: 0.18).cgColor
                : UIColor(white: 0.0, alpha: 0.12).cgColor
        }
        fallbackTrackView.layer.borderWidth = 0.75
        if let blur = backgroundBlurView {
            blur.layer.borderColor = fallbackTrackView.layer.borderColor
            blur.layer.borderWidth = glassStyle ? 0.75 : 0
        }

        let activeIdx = dockMode ? hoverIndex : selectedIndex
        let activeSeg = (activeIdx >= 0 && activeIdx < segments.count) ? segments[activeIdx] : nil
        if dockMode, let seg = activeSeg, seg.isDestructive {
            pillView.backgroundColor = UIColor(red: 217/255, green: 56/255, blue: 56/255, alpha: darkMode ? 0.34 : 0.16)
            pillView.layer.borderColor = UIColor(red: 217/255, green: 56/255, blue: 56/255, alpha: 0.42).cgColor
        } else if dockMode, let seg = activeSeg, seg.isAccent {
            pillView.backgroundColor = accentColor.withAlphaComponent(darkMode ? 0.32 : 0.16)
            pillView.layer.borderColor = accentColor.withAlphaComponent(0.45).cgColor
        } else if glassStyle {
            pillView.backgroundColor = darkMode
                ? UIColor(red: 55/255, green: 61/255, blue: 74/255, alpha: 0.86)
                : UIColor(white: 1.0, alpha: 0.96)
            pillView.layer.borderColor = darkMode
                ? UIColor(white: 1.0, alpha: 0.24).cgColor
                : UIColor(white: 0.0, alpha: 0.08).cgColor
        } else {
            pillView.backgroundColor = darkMode
                ? UIColor(red: 52/255, green: 56/255, blue: 64/255, alpha: 1.0)
                : .white
            pillView.layer.borderColor = darkMode
                ? UIColor(white: 1.0, alpha: 0.14).cgColor
                : UIColor(white: 0.0, alpha: 0.10).cgColor
        }
        pillView.layer.borderWidth = 0.75

        for (i, seg) in segments.enumerated() where i < itemButtons.count {
            let btn = itemButtons[i]
            let enabled = isEnabled(i)
            let active = dockMode ? (i == hoverIndex) : (i == selectedIndex)
            let color: UIColor
            if !enabled {
                color = darkMode ? UIColor(white: 0.65, alpha: 0.35) : UIColor(white: 0.55, alpha: 0.35)
            } else if seg.isDestructive {
                color = darkMode
                    ? UIColor(red: 1.0, green: 112/255, blue: 112/255, alpha: 1.0)
                    : UIColor(red: 209/255, green: 52/255, blue: 56/255, alpha: 1.0)
            } else if seg.isAccent || active {
                color = accentColor
            } else {
                color = darkMode
                    ? UIColor(red: 184/255, green: 192/255, blue: 204/255, alpha: 1.0)
                    : UIColor(red: 85/255, green: 94/255, blue: 107/255, alpha: 1.0)
            }
            btn.tintColor = color
            btn.setTitleColor(color, for: .normal)
            btn.alpha = enabled ? 1.0 : 0.45
        }
    }

    private func continuousIndex(x: CGFloat) -> CGFloat {
        guard segments.count > 1 else { return 0 }
        let inset: CGFloat = dockMode ? 5 : 3
        let trackRect = bounds.insetBy(dx: inset, dy: inset)
        let segW = max(1, trackRect.width / CGFloat(segments.count))
        let pos = (x - trackRect.minX - segW * 0.5) / segW
        return max(0, min(CGFloat(segments.count - 1), pos))
    }

    private func indexAt(x: CGFloat) -> Int {
        guard !segments.isEmpty else { return -1 }
        return clampIndex(Int(round(continuousIndex(x: x))))
    }

    private func isEnabled(_ index: Int) -> Bool {
        return index >= 0 && index < enabledFlags.count && enabledFlags[index]
    }

    private func clampIndex(_ index: Int) -> Int {
        guard !segments.isEmpty else { return 0 }
        return max(0, min(segments.count - 1, index))
    }
}
