@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.naviamp.ios

import platform.UIKit.UIStatusBarStyle
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController
import platform.UIKit.removeFromParentViewController
import platform.UIKit.willMoveToParentViewController

/** Owns status-bar icon contrast without changing the system traits observed by shared Compose. */
internal class IosThemeViewController(
    content: (setLightSystemBarIcons: (Boolean) -> Unit) -> UIViewController,
) : UIViewController(nibName = null, bundle = null) {
    // Startup fallback only; shared content supplies the effective contrast.
    private var lightSystemBarIcons = true
    private var contentViewController: UIViewController? = content(::applySystemBarIconContrast)

    override fun viewDidLoad() {
        super.viewDidLoad()
        val child = contentViewController ?: return
        addChildViewController(child)
        child.view.setFrame(view.bounds)
        child.view.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        view.addSubview(child.view)
        child.didMoveToParentViewController(this)
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        contentViewController?.view?.setFrame(view.bounds)
    }

    override fun preferredStatusBarStyle(): UIStatusBarStyle =
        if (lightSystemBarIcons) UIStatusBarStyleLightContent else UIStatusBarStyleDarkContent

    private fun applySystemBarIconContrast(light: Boolean) {
        if (lightSystemBarIcons == light) return
        lightSystemBarIcons = light
        setNeedsStatusBarAppearanceUpdate()
    }

    fun close() {
        val child = contentViewController ?: return
        contentViewController = null
        if (child.parentViewController != null) {
            child.willMoveToParentViewController(null)
            child.view.removeFromSuperview()
            child.removeFromParentViewController()
        }
    }
}
