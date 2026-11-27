import UIKit

/// Native anchored translucent menu shared by Album and LiveStudio.
/// Long hold + drag + release commits exactly once; stationary hold supports taps.
final class LiveQuickMenu: NSObject {
    private weak var anchor: UIView?
    private var overlay: UIControl?
    private var panel: UIVisualEffectView?
    private var scroll: UIScrollView?
    private var rows: [UIButton] = []
    private var selection: ((Int) -> Void)?
    private var current = -1
    private var highlighted = -1
    private var start = CGPoint.zero
    private var moved = false

    var isOpen: Bool { overlay != nil }
    func dismiss() {
        overlay?.removeFromSuperview()
        overlay = nil; panel = nil; scroll = nil
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
            if hypot(p.x-start.x,p.y-start.y)>8 { moved = true }
            if moved { update(p, in: window) }
        case .ended:
            if moved {
                update(gesture.location(in: window), in: window)
                if highlighted >= 0 { commit(highlighted) } else { dismiss() }
            }
        case .cancelled, .failed: dismiss()
        default: break
        }
    }

    func show(anchor target: UIView, labels: [String], selected: Int, onSelect: @escaping (Int) -> Void) {
        dismiss()
        guard let window = target.window, !labels.isEmpty else { return }
        anchor=target; current=selected; selection=onSelect
        let cover=UIControl(frame: window.bounds)
        cover.autoresizingMask=[.flexibleWidth,.flexibleHeight]
        cover.backgroundColor=UIColor.black.withAlphaComponent(0.035)
        cover.addTarget(self,action:#selector(cancelTapped),for:.touchUpInside)
        cover.accessibilityViewIsModal=true
        let safe=window.bounds.inset(by:window.safeAreaInsets).insetBy(dx:8,dy:8)
        let rect=target.convert(target.bounds,to:window)
        let below=rect.maxY+6, above=rect.minY-6
        let desired=CGFloat(labels.count)*44+12
        let down=safe.maxY-below >= min(desired,above-safe.minY)
        let available=down ? safe.maxY-below : above-safe.minY
        guard available>=88, safe.width>80 else { selection=nil; return }
        let width=min(248,safe.width), height=min(desired,available)
        let x=max(safe.minX,min(safe.maxX-width,rect.maxX-width))
        let y=down ? below : above-height
        let glass=UIVisualEffectView(effect:UIBlurEffect(style:.systemThickMaterial))
        glass.frame=CGRect(x:x,y:y,width:width,height:height)
        glass.layer.cornerRadius=23
        glass.layer.borderWidth=0.5
        glass.layer.borderColor=UIColor.separator.cgColor
        glass.clipsToBounds=true
        let sc=UIScrollView(frame:glass.bounds)
        sc.autoresizingMask=[.flexibleWidth,.flexibleHeight]
        sc.showsVerticalScrollIndicator=false
        sc.contentSize=CGSize(width:width,height:desired)
        for (i,label) in labels.enumerated() {
            let row=UIButton(type:.system)
            row.frame=CGRect(x:6,y:6+CGFloat(i)*44,width:width-12,height:44)
            row.tag=i
            row.setTitle(label+(i==selected ? "  ✓" : ""),for:.normal)
            row.titleLabel?.font=UIFont.systemFont(ofSize:14,weight:.medium)
            row.titleLabel?.numberOfLines=2
            row.setTitleColor(.label,for:.normal)
            row.contentHorizontalAlignment = .left
            row.contentEdgeInsets=UIEdgeInsets(top:0,left:13,bottom:0,right:10)
            row.layer.cornerRadius=14
            row.accessibilityLabel=label
            if i==selected { row.accessibilityTraits.insert(.selected) }
            row.addTarget(self,action:#selector(rowTapped(_:)),for:.touchUpInside)
            sc.addSubview(row); rows.append(row)
        }
        glass.contentView.addSubview(sc); cover.addSubview(glass); window.addSubview(cover)
        overlay=cover; panel=glass; scroll=sc; paint(-1)
        if !UIAccessibility.isReduceMotionEnabled {
            glass.alpha=0; glass.transform=CGAffineTransform(scaleX:0.94,y:0.94)
            UIView.animate(withDuration:0.23,delay:0,usingSpringWithDamping:0.87,initialSpringVelocity:0.3,options:[.allowUserInteraction],animations:{
                glass.alpha=1; glass.transform = .identity
            })
        }
        UIAccessibility.post(notification:.layoutChanged,argument:rows.first)
    }
    private func paint(_ hit:Int) {
        for (i,row) in rows.enumerated() {
            row.backgroundColor = i==hit ? UIColor.systemBlue.withAlphaComponent(0.17)
                : (i==current ? UIColor.tertiarySystemFill : .clear)
        }
    }
    private func update(_ p:CGPoint,in window:UIWindow) {
        guard let glass=panel,let sc=scroll else {return}
        let q=glass.convert(p,from:window)
        var hit = -1
        if glass.bounds.contains(q) {
            if sc.contentSize.height>sc.bounds.height {
                if q.y>glass.bounds.height-24 {sc.contentOffset.y=min(sc.contentSize.height-sc.bounds.height,sc.contentOffset.y+12)}
                else if q.y<24 {sc.contentOffset.y=max(0,sc.contentOffset.y-12)}
            }
            for (i,row) in rows.enumerated() where row.bounds.contains(row.convert(p,from:window)) {hit=i;break}
        }
        if hit != highlighted {
            highlighted=hit; paint(hit)
            if hit>=0 { UISelectionFeedbackGenerator().selectionChanged() }
        }
    }
    private func commit(_ n:Int) {
        let callback=selection
        dismiss(); callback?(n)
    }
    @objc private func cancelTapped(){dismiss()}
    @objc private func rowTapped(_ sender:UIButton){commit(sender.tag)}
}
