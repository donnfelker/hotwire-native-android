package dev.hotwire.core.turbo.nav

import android.os.Bundle
import androidx.annotation.IdRes
import androidx.fragment.app.FragmentManager
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.FragmentNavigator
import dev.hotwire.core.bridge.Bridge
import dev.hotwire.core.config.Hotwire
import dev.hotwire.core.config.Hotwire.pathConfiguration
import dev.hotwire.core.lib.logging.logEvent
import dev.hotwire.core.navigation.routing.Router
import dev.hotwire.core.navigation.session.NavigatorHost
import dev.hotwire.core.turbo.session.Session
import dev.hotwire.core.turbo.util.location
import dev.hotwire.core.turbo.visit.VisitAction
import dev.hotwire.core.turbo.visit.VisitOptions

class Navigator(val host: NavigatorHost) {
    private val navController = host.navController

    /**
     * Retrieves the currently active [HotwireNavDestination] on the backstack.
     */
    val currentDestination: HotwireNavDestination
        get() = host.childFragmentManager.primaryNavigationFragment as HotwireNavDestination?
            ?: throw IllegalStateException("No current destination found in NavHostFragment")

    /**
     * Gets the location for the current destination.
     */
    val location: String?
        get() = navController.currentBackStackEntry?.location

    /**
     * Gets the location for the previous destination on the backstack.
     */
    val previousLocation: String?
        get() = navController.previousBackStackEntry?.location

    /**
     * The [Session] instance that is shared with all destinations that are
     * hosted inside this [NavigatorHost].
     */
    var session = createNewSession()
        private set

    internal fun createNewSession() = Session(
        sessionName = host.sessionConfiguration.name,
        activity = host.activity,
        webView = Hotwire.config.makeCustomWebView(host.requireContext())
    ).also {
        // Initialize bridge with new WebView instance
        if (Hotwire.registeredBridgeComponentFactories.isNotEmpty()) {
            Bridge.initialize(it.webView)
        }
    }

    fun isAtStartDestination(): Boolean {
        return navController.previousBackStackEntry == null
    }

    /**
     * Navigates up to the previous destination.
     */
    fun navigateUp() {
        navigateWhenReady {
            val currentFragment = currentDestination.fragment
            if (currentFragment is HotwireNavDialogDestination) {
                currentFragment.closeDialog()
            } else {
                navController.navigateUp()
            }
        }
    }

    /**
     * Navigates back to the previous destination. See [NavController.popBackStack] for
     * more details.
     */
    fun navigateBack() {
        navigateWhenReady {
            val currentFragment = currentDestination.fragment
            if (currentFragment is HotwireNavDialogDestination) {
                currentFragment.closeDialog()
            } else {
                navController.popBackStack()
            }
        }
    }

    /**
     * Navigates to the specified location. The resulting destination and its presentation
     * will be determined using the path configuration rules.
     *
     * @param location The location to navigate to.
     * @param options Visit options to apply to the visit. (optional)
     * @param bundle Bundled arguments to pass to the destination. (optional)
     * @param extras Extras that can be passed to enable Fragment specific behavior. (optional)
     */
    fun navigate(
        location: String,
        options: VisitOptions = VisitOptions(),
        bundle: Bundle? = null,
        extras: FragmentNavigator.Extras? = null
    ) {

        if (getRouteResult(location) == Router.RouteResult.STOP) {
            return
        }

        val rule = TurboNavRule(
            location = location,
            visitOptions = options,
            bundle = bundle,
            navOptions = navOptions(location, options.action),
            extras = extras,
            pathConfiguration = pathConfiguration,
            controller = currentControllerForLocation(location)
        )

        logEvent(
            "navigate", "location" to rule.newLocation,
            "options" to options,
            "currentContext" to rule.currentPresentationContext,
            "newContext" to rule.newPresentationContext,
            "presentation" to rule.newPresentation
        )

        when (rule.newNavigationMode) {
            TurboNavMode.DISMISS_MODAL -> {
                dismissModalContextWithResult(rule)
            }
            TurboNavMode.TO_MODAL -> {
                navigateToModalContext(rule)
            }
            TurboNavMode.IN_CONTEXT -> {
                navigateWithinContext(rule)
            }
            TurboNavMode.REFRESH -> {
                navigate(rule.currentLocation, VisitOptions())
            }
            TurboNavMode.NONE -> {
                // Do nothing
            }
        }
    }

    /**
     * Clears the navigation back stack to the start destination.
     */
    fun clearBackStack(onCleared: () -> Unit = {}) {
        if (isAtStartDestination()) {
            onCleared()
            return
        }

        navigateWhenReady {
            val currentFragment = currentDestination.fragment
            if (currentFragment is HotwireNavDialogDestination) {
                currentFragment.closeDialog()
            }

            navController.popBackStack(navController.graph.startDestinationId, false)
            onCleared()
        }
    }

    /**
     * Resets the [Navigator] along with its [NavigatorHost] and [Session] instances.
     * The entire navigation graph is reset to its original starting point.
     */
    fun reset(onReset: () -> Unit = {}) {
        navigateWhenReady {
            clearBackStack {
                session.reset()
                host.initControllerGraph()

                if (host.view == null) {
                    onReset()
                } else {
                    host.requireView().post { onReset() }
                }
            }
        }
    }

    /**
     * Finds the [NavigatorHost] with the given resource ID.
     */
    fun findNavHostFragment(@IdRes navHostFragmentId: Int): NavigatorHost {
        val fragment = currentDestination.fragment

        return fragment.parentFragment?.childFragmentManager?.findNavHostFragment(navHostFragmentId)
            ?: fragment.parentFragment?.parentFragment?.childFragmentManager?.findNavHostFragment(navHostFragmentId)
            ?: fragment.requireActivity().supportFragmentManager.findNavHostFragment(navHostFragmentId)
            ?: throw IllegalStateException("No NavigatorHost found with ID: $navHostFragmentId")
    }

    private fun navigateWhenReady(onReady: () -> Unit) {
        currentDestination.onBeforeNavigation()
        currentDestination.prepareNavigation(onReady)
    }

    private fun navigateWithinContext(rule: TurboNavRule) {
        logEvent(
            "navigateWithinContext",
            "location" to rule.newLocation,
            "presentation" to rule.newPresentation
        )

        when (rule.newPresentation) {
            TurboNavPresentation.POP -> navigateWhenReady {
                popBackStack(rule)
            }
            TurboNavPresentation.REPLACE -> navigateWhenReady {
                popBackStack(rule)
                navigateToLocation(rule)
            }
            TurboNavPresentation.PUSH -> navigateWhenReady {
                navigateToLocation(rule)
            }
            TurboNavPresentation.REPLACE_ROOT -> navigateWhenReady {
                replaceRootLocation(rule)
            }
            TurboNavPresentation.CLEAR_ALL -> navigateWhenReady {
                clearBackStack()
            }
            else -> {
                throw IllegalStateException("Unexpected Presentation for navigating within context")
            }
        }
    }

    private fun navigateToModalContext(rule: TurboNavRule) {
        logEvent(
            "navigateToModalContext",
            "location" to rule.newLocation
        )

        when (rule.newPresentation) {
            TurboNavPresentation.REPLACE -> navigateWhenReady {
                popBackStack(rule)
                navigateToLocation(rule)
            }
            else -> navigateWhenReady {
                navigateToLocation(rule)
            }
        }
    }

    private fun dismissModalContextWithResult(rule: TurboNavRule) {
        logEvent(
            "dismissModalContextWithResult",
            "location" to rule.newLocation,
            "uri" to rule.newDestinationUri,
            "presentation" to rule.newPresentation
        )

        navigateWhenReady {
            val isDialog = currentDestination.fragment is HotwireNavDialogDestination
            if (isDialog) {
                // Pop the backstack before sending the modal result, since the
                // underlying fragment is still active and will receive the
                // result immediately. This allows the modal result flow to
                // behave exactly like full screen fragments.
                popModalsFromBackStack(rule)
                sendModalResult(rule)
            } else {
                sendModalResult(rule)
                popModalsFromBackStack(rule)
            }
        }
    }

    private fun popModalsFromBackStack(rule: TurboNavRule) {
        do {
            popBackStack(rule)
        } while (
            rule.controller.currentBackStackEntry.isModalContext
        )
    }

    private fun popBackStack(rule: TurboNavRule) {
        logEvent(
            "popFromBackStack",
            "location" to rule.controller.currentBackStackEntry.location.orEmpty()
        )
        rule.controller.popBackStack()
    }

    private fun sendModalResult(rule: TurboNavRule) {
        // Save the modal result with VisitOptions so it can be retrieved
        // by the previous destination when the backstack is popped.
        currentDestination.delegate().sessionViewModel.sendModalResult(
            checkNotNull(rule.newModalResult)
        )
    }

    private fun replaceRootLocation(rule: TurboNavRule) {
        if (rule.newDestination == null) {
            logEvent(
                "replaceRootLocation",
                "location" to rule.newLocation,
                "error" to "No destination found",
                "uri" to rule.newDestinationUri
            )
            return
        }

        logEvent(
            "replaceRootLocation",
            "location" to rule.newLocation,
            "uri" to rule.newDestinationUri
        )
        rule.controller.navigate(rule.newDestination.id, rule.newBundle, rule.newNavOptions)
    }

    private fun navigateToLocation(rule: TurboNavRule) {
        // Save the VisitOptions so it can be retrieved by the next
        // destination. When response.responseHTML is present it is
        // too large to save directly within the args bundle.
        currentDestination.delegate().sessionViewModel.saveVisitOptions(rule.newVisitOptions)

        rule.newDestination?.let {
            logEvent(
                "navigateToLocation",
                "location" to rule.newLocation,
                "uri" to rule.newDestinationUri
            )
            rule.controller.navigate(it.id, rule.newBundle, rule.newNavOptions, rule.newExtras)
            return
        }

        logEvent(
            "navigateToLocation",
            "location" to rule.newLocation,
            "warning" to "No destination found",
            "uri" to rule.newDestinationUri
        )

        rule.newFallbackDestination?.let {
            logEvent(
                "navigateToLocation",
                "location" to rule.newLocation,
                "fallbackUri" to "${rule.newFallbackUri}"
            )
            rule.controller.navigate(it.id, rule.newBundle, rule.newNavOptions, rule.newExtras)
            return
        }

        logEvent(
            "navigateToLocation",
            "location" to rule.newLocation,
            "error" to "No fallback destination found"
        )
    }

    private fun currentControllerForLocation(location: String): NavController {
        return currentDestination.navHostForNavigation(location).navController
    }

    private fun getRouteResult(location: String): Router.RouteResult {
        val result = currentDestination.route(location)

        logEvent(
            "routeResult",
            "location" to location,
            "result" to result
        )
        return result
    }

    private fun navOptions(location: String, action: VisitAction): NavOptions {
        val properties = pathConfiguration.properties(location)

        return currentDestination.getNavigationOptions(
            newLocation = location,
            newPathProperties = properties,
            action = action
        )
    }

    private fun FragmentManager.findNavHostFragment(navHostFragmentId: Int): NavigatorHost? {
        return findFragmentById(navHostFragmentId) as? NavigatorHost
    }

    private val NavBackStackEntry?.isModalContext: Boolean
        get() {
            val context = this?.arguments?.getSerializable("presentation-context")
            return context as? TurboNavPresentationContext == TurboNavPresentationContext.MODAL
        }

    private fun logEvent(event: String, vararg params: Pair<String, Any>) {
        val attributes = params.toMutableList().apply {
            add(0, "session" to session.sessionName)
            add("currentFragment" to currentDestination.fragment.javaClass.simpleName)
        }
        logEvent(event, attributes)
    }
}
