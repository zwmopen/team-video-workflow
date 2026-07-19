import UIKit

enum AppColors {
    static var groupedTableStyle: UITableView.Style {
        if #available(iOS 13.0, *) { return .insetGrouped }
        return .grouped
    }
    static var background: UIColor {
        if #available(iOS 13.0, *) { return .systemBackground }
        return .white
    }
    static var secondaryBackground: UIColor {
        if #available(iOS 13.0, *) { return .secondarySystemBackground }
        return UIColor(white: 0.96, alpha: 1)
    }
    static var sharedBackground: UIColor {
        if #available(iOS 13.0, *) { return .secondarySystemFill }
        return UIColor(white: 0.91, alpha: 1)
    }
    static var text: UIColor {
        if #available(iOS 13.0, *) { return .label }
        return .black
    }
    static var secondaryText: UIColor {
        if #available(iOS 13.0, *) { return .secondaryLabel }
        return UIColor(white: 0.42, alpha: 1)
    }
    static var separator: UIColor {
        if #available(iOS 13.0, *) { return .separator }
        return UIColor(white: 0.78, alpha: 1)
    }
}
