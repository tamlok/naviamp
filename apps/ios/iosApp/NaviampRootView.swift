import NaviampShared
import UIKit

final class NaviampRootView {
    private let application: NaviampIosApplication
    let viewController: UIViewController
    private var closed = false

    init() {
        let application = NaviampIosApplication(
            applicationSupportDirectory: IosApplicationDirectories.supportDirectory(),
            credentialProtector: IosKeychainCredentialProtector()
        )
        self.application = application
        viewController = application.viewController()
    }

    func close() {
        guard !closed else {
            return
        }
        closed = true
        application.close()
    }

    deinit {
        close()
    }
}
