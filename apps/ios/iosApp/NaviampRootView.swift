import NaviampShared
import UIKit

final class NaviampRootView {
    private let application: NaviampIosApplication
    let viewController: UIViewController
    private var closed = false

    @MainActor
    init() {
        let application = NaviampApplicationDelegate.runtime.application()
        self.application = application
        viewController = application.viewController()
    }

    func close() {
        guard !closed else {
            return
        }
        closed = true
        application.releaseViewController(controller: viewController)
    }

    deinit {
        close()
    }
}
