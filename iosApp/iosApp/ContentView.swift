import UIKit
import SwiftUI
import ComposeApp

final class RootComposeViewController: UIViewController, UITabBarDelegate {
    private enum NativeTab: String, CaseIterable {
        case home = "Home"
        case search = "Search"
        case library = "Library"
        case settings = "Settings"

        var tag: Int {
            switch self {
            case .home: return 0
            case .search: return 1
            case .library: return 2
            case .settings: return 3
            }
        }

        var title: String {
            switch self {
            case .home: return "Home"
            case .search: return "Search"
            case .library: return "Library"
            case .settings: return "Profile"
            }
        }

        var systemImageName: String {
            switch self {
            case .home: return "house"
            case .search: return "magnifyingglass"
            case .library: return "books.vertical"
            case .settings: return "person.crop.circle"
            }
        }

        init?(tag: Int) {
            guard let tab = Self.allCases.first(where: { $0.tag == tag }) else { return nil }
            self = tab
        }
    }

    private static let liquidGlassEnabledKey = "NuvioLiquidGlassNativeTabBarEnabled"
    private static let nativeTabBarVisibleKey = "NuvioNativeTabBarVisible"
    private static let nativeSelectedTabKey = "NuvioNativeSelectedTab"
    private static let nativeTabChromeDidChangeNotification = Notification.Name("NuvioNativeTabChromeDidChange")

    private let contentController: UIViewController
    private let tabBar = UITabBar()
    private var contentBottomToViewBottom: NSLayoutConstraint?
    private var tabBarHeightConstraint: NSLayoutConstraint?
    private var userDefaultsObserver: NSObjectProtocol?
    private var tabChromeObserver: NSObjectProtocol?

    init(contentController: UIViewController) {
        self.contentController = contentController
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = .black
        contentController.view.backgroundColor = .black
        UserDefaults.standard.set(false, forKey: Self.nativeTabBarVisibleKey)

        addChild(contentController)
        view.addSubview(contentController.view)
        contentController.view.translatesAutoresizingMaskIntoConstraints = false
        let bottomToViewBottom = contentController.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        self.contentBottomToViewBottom = bottomToViewBottom
        NSLayoutConstraint.activate([
            contentController.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            contentController.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            contentController.view.topAnchor.constraint(equalTo: view.topAnchor),
            bottomToViewBottom,
        ])
        contentController.didMove(toParent: self)

        configureNativeTabBar()
        installNativeTabObservers()
        syncNativeTabChrome(animated: false)
    }

    deinit {
        if let userDefaultsObserver {
            NotificationCenter.default.removeObserver(userDefaultsObserver)
        }
        if let tabChromeObserver {
            NotificationCenter.default.removeObserver(tabChromeObserver)
        }
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        updateTabBarHeight()
    }

    func tabBar(_ tabBar: UITabBar, didSelect item: UITabBarItem) {
        guard let tab = NativeTab(tag: item.tag) else { return }
        UserDefaults.standard.set(tab.rawValue, forKey: Self.nativeSelectedTabKey)
        NativeTabBridgeKt.nativeTabSelect(tabName: tab.rawValue)
    }

    override var childForHomeIndicatorAutoHidden: UIViewController? {
        immersiveController(in: contentController) ?? contentController
    }

    override var childForScreenEdgesDeferringSystemGestures: UIViewController? {
        immersiveController(in: contentController) ?? contentController
    }

    override var childForStatusBarHidden: UIViewController? {
        immersiveController(in: contentController) ?? contentController
    }

    override var prefersHomeIndicatorAutoHidden: Bool {
        immersiveController(in: contentController)?.prefersHomeIndicatorAutoHidden ?? false
    }

    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        immersiveController(in: contentController)?.preferredScreenEdgesDeferringSystemGestures ?? []
    }

    override var prefersStatusBarHidden: Bool {
        immersiveController(in: contentController)?.prefersStatusBarHidden ?? false
    }

    override var preferredStatusBarUpdateAnimation: UIStatusBarAnimation {
        .fade
    }

    func refreshImmersiveSystemUI() {
        setNeedsUpdateOfHomeIndicatorAutoHidden()
        setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
        setNeedsStatusBarAppearanceUpdate()
    }

    private func immersiveController(in controller: UIViewController?) -> UIViewController? {
        guard let controller else { return nil }

        if controller.prefersHomeIndicatorAutoHidden ||
            !controller.preferredScreenEdgesDeferringSystemGestures.isEmpty ||
            controller.prefersStatusBarHidden {
            return controller
        }

        if let presented = immersiveController(in: controller.presentedViewController) {
            return presented
        }

        for child in controller.children.reversed() {
            if let immersiveChild = immersiveController(in: child) {
                return immersiveChild
            }
        }

        return nil
    }

    private var nativeTabsSupported: Bool {
        UIDevice.current.userInterfaceIdiom == .phone &&
            ProcessInfo.processInfo.operatingSystemVersion.majorVersion >= 26
    }

    private var shouldShowNativeTabBar: Bool {
        nativeTabsSupported &&
            UserDefaults.standard.bool(forKey: Self.liquidGlassEnabledKey) &&
            UserDefaults.standard.bool(forKey: Self.nativeTabBarVisibleKey)
    }

    private func configureNativeTabBar() {
        tabBar.delegate = self
        tabBar.translatesAutoresizingMaskIntoConstraints = false
        tabBar.items = NativeTab.allCases.map { tab in
            UITabBarItem(
                title: tab.title,
                image: UIImage(systemName: tab.systemImageName),
                tag: tab.tag
            )
        }
        tabBar.selectedItem = tabBar.items?.first
        tabBar.alpha = 0
        tabBar.isHidden = true

        view.addSubview(tabBar)
        let heightConstraint = tabBar.heightAnchor.constraint(equalToConstant: tabBarHeight)
        tabBarHeightConstraint = heightConstraint
        NSLayoutConstraint.activate([
            tabBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tabBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tabBar.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            heightConstraint,
        ])
    }

    private func installNativeTabObservers() {
        userDefaultsObserver = NotificationCenter.default.addObserver(
            forName: UserDefaults.didChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.syncNativeTabChrome(animated: true)
        }

        tabChromeObserver = NotificationCenter.default.addObserver(
            forName: Self.nativeTabChromeDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.syncNativeTabChrome(animated: true)
        }
    }

    private var tabBarHeight: CGFloat {
        49 + view.safeAreaInsets.bottom
    }

    private func updateTabBarHeight() {
        tabBarHeightConstraint?.constant = tabBarHeight
    }

    private func syncNativeTabChrome(animated: Bool) {
        updateTabBarHeight()
        syncSelectedNativeTab()

        let visible = shouldShowNativeTabBar
        contentBottomToViewBottom?.isActive = true
        if visible {
            tabBar.isHidden = false
        }

        let changes = {
            self.tabBar.alpha = visible ? 1 : 0
            self.view.layoutIfNeeded()
        }

        let completion: (Bool) -> Void = { _ in
            self.tabBar.isHidden = !visible
        }

        if animated && view.window != nil {
            UIView.animate(
                withDuration: 0.22,
                delay: 0,
                options: [.beginFromCurrentState, .curveEaseInOut],
                animations: changes,
                completion: completion
            )
        } else {
            changes()
            completion(true)
        }
    }

    private func syncSelectedNativeTab() {
        let rawValue = UserDefaults.standard.string(forKey: Self.nativeSelectedTabKey) ?? NativeTab.home.rawValue
        let selectedTab = NativeTab(rawValue: rawValue) ?? .home
        tabBar.selectedItem = tabBar.items?.first(where: { $0.tag == selectedTab.tag })
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // Register MPV player bridge before Compose initializes
        NuvioPlayerRegistration.register()
        
        let controller = MainViewControllerKt.MainViewController()
        controller.view.backgroundColor = UIColor(red: 0.008, green: 0.016, blue: 0.016, alpha: 1.0)
        return RootComposeViewController(contentController: controller)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
