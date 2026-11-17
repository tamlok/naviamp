import UIKit

@main
final class NaviampApp: UIResponder, UIApplicationDelegate {
    private var rootOwner: NaviampRootView?

    func rootView() -> NaviampRootView {
        if let rootOwner {
            return rootOwner
        }
        let owner = NaviampRootView()
        rootOwner = owner
        return owner
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        if connectingSceneSession.role == .windowApplication {
            configuration.sceneClass = UIWindowScene.self
            configuration.delegateClass = NaviampSceneDelegate.self
            configuration.storyboard = nil
        }
        return configuration
    }

    func applicationWillTerminate(_ application: UIApplication) {
        rootOwner?.close()
    }
}

final class NaviampSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard session.role == .windowApplication,
              let windowScene = scene as? UIWindowScene,
              let applicationDelegate = UIApplication.shared.delegate as? NaviampApp else {
            return
        }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = applicationDelegate.rootView().viewController
        self.window = window
        window.makeKeyAndVisible()
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        // The process owner retains playback, Core and the controller for a later reconnect.
        window = nil
    }
}
