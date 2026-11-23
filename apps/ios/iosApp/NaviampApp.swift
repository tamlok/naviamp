import UIKit

@main
final class NaviampApp: NaviampApplicationDelegate {

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        guard connectingSceneSession.role == .windowApplication else {
            return connectingSceneSession.configuration
        }
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.sceneClass = UIWindowScene.self
        configuration.delegateClass = NaviampSceneDelegate.self
        configuration.storyboard = nil
        return configuration
    }
}

final class NaviampSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private var rootOwner: NaviampRootView?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard session.role == .windowApplication,
              let windowScene = scene as? UIWindowScene else {
            return
        }
        let owner = NaviampRootView()
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = owner.viewController
        rootOwner = owner
        self.window = window
        window.makeKeyAndVisible()
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        // Only this scene's UIKit content is released; the common owner retains playback for CarPlay.
        window?.rootViewController = nil
        rootOwner?.close()
        rootOwner = nil
        window = nil
    }
}
