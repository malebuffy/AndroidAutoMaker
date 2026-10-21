package com.automaker.app.engine

import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.io.BlockReader
import com.reandroid.arsc.value.ValueType

object ManifestPatcher {
    const val AUTOMOTIVE_FEATURE = "android.hardware.type.automotive"
    const val GAMEPAD_FEATURE = "android.hardware.gamepad"
    const val LAUNCHER = "android.intent.category.LAUNCHER"
    const val CAR_LAUNCHER = "android.intent.category.CAR_LAUNCHER"
    const val MAIN = "android.intent.action.MAIN"
    const val DERIVED_APK_ID = "com.android.vending.derived.apk.id"
    const val DISTRACTION = "distractionOptimized"
    const val GMS_CAR = "com.google.android.gms.car.application"
    const val APP_MAPS = "android.intent.category.APP_MAPS"
    const val CAR_NAVIGATION = "androidx.car.app.category.NAVIGATION"
    const val CAR_APP_SERVICE = "androidx.car.app.CarAppService"
    const val MIN_CAR_API = "androidx.car.app.minCarApiLevel"
    const val NAVIGATION_XML = "res/xml/automotive_app_desc.xml"
    private val NAVIGATION_USES = arrayOf("media", "template", "service", "projection")

    private const val ID_APP_CATEGORY = 0x01010545
    private const val ID_IS_GAME = 0x010103f4
    private const val ID_REQUIRED = 0x0101028e
    private val CATEGORIES = arrayOf(
        "game",
        "audio",
        "video",
        "image",
        "social",
        "news",
        "maps",
        "productivity",
        "accessibility",
    )

    fun patch(manifest: AndroidManifestBlock, apk: ApkModule, kind: String = "game"): List<String> {
        val navigation = kind == "navigation"
        val changes = ArrayList<String>()
        val application = manifest.applicationElement
            ?: throw IllegalStateException("AndroidManifest.xml has no <application> element.")
        removeMeta(manifest, DERIVED_APK_ID, changes, "Removed the Play-derived APK id so the resigned package can be installed.")
        if (navigation) {
            clearGameDeclaration(application, changes)
        } else {
            if (removeMeta(manifest, DISTRACTION, changes, null) || removeDistractionAttributes(manifest)) {
                changes.add("Removed distraction-optimized flags so Android Auto keeps the app in the parked category.")
            }
            declareGame(application, changes)
        }
        ensureAutomotiveFeature(manifest, changes)
        ensureGamepadFeature(manifest, changes)
        ensureCarLauncher(manifest, changes, navigation)
        ensureExported(manifest, changes)
        if (navigation) {
            ensureDistractionOptimized(manifest, changes)
            ensurePermission(manifest, "androidx.car.app.NAVIGATION_TEMPLATES", changes, "Added the navigation templates permission.")
            ensurePermission(manifest, "androidx.car.app.ACCESS_SURFACE", changes, "Added the car surface permission.")
            ensureMinCarApi(application, changes, "Set the car API level to 5.")
            ensureNavigationDescriptor(apk, application, changes)
            ensureNavigationService(manifest, application, changes)
        }
        manifest.fixNamespaces()
        manifest.fixAttributeNames()
        return listOf(if (navigation) "Prepared as navigation." else "Prepared as a parked game.")
    }

    private fun declareGame(application: ResXmlElement, changes: MutableList<String>) {
        val previous = findAttr(application, ID_APP_CATEGORY, "appCategory")
        val previousGame = findAttr(application, ID_IS_GAME, "isGame")
        val previousName = categoryName(previous)
        val already = previousName == "game" && previousGame != null && isTrue(previousGame)
        val category = application.getOrCreateAndroidAttribute("appCategory", ID_APP_CATEGORY)
        category.setTypeAndData(ValueType.DEC, 0)
        val game = application.getOrCreateAndroidAttribute("isGame", ID_IS_GAME)
        game.setValueAsBoolean(true)
        if (already) {
            changes.add("The app was already declared as a game.")
        } else if (previousName != null && previousName != "game") {
            changes.add("Changed the app category from $previousName to game so Android Auto can show it while parked.")
        } else {
            changes.add("Declared the app as a game so Android Auto can show its screen while parked.")
        }
    }

    private fun ensureAutomotiveFeature(manifest: AndroidManifestBlock, changes: MutableList<String>) {
        ensureOptionalFeature(
            manifest,
            AUTOMOTIVE_FEATURE,
            changes,
            already = "Automotive hardware was already optional, so the APK still installs on a phone.",
            marked = "Marked automotive hardware as optional so the APK still installs on a phone.",
            added = "Declared optional automotive hardware so the same APK installs on a phone and in the car.",
        )
    }

    private fun ensureGamepadFeature(manifest: AndroidManifestBlock, changes: MutableList<String>) {
        ensureOptionalFeature(
            manifest,
            GAMEPAD_FEATURE,
            changes,
            already = "Game controller support was already declared.",
            marked = "Marked game controller support as optional.",
            added = "Added game controller support.",
        )
    }

    private fun ensureOptionalFeature(
        manifest: AndroidManifestBlock,
        featureName: String,
        changes: MutableList<String>,
        already: String,
        marked: String,
        added: String,
    ) {
        val root = manifest.manifestElement
            ?: throw IllegalStateException("AndroidManifest.xml has no <manifest> element.")
        val existing = elementsNamed(root, AndroidManifest.TAG_uses_feature).firstOrNull { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == featureName
        }
        if (existing != null) {
            val required = findAttr(existing, ID_REQUIRED, "required")
            if (required != null && isFalse(required)) {
                changes.add(already)
                return
            }
            existing.getOrCreateAndroidAttribute("required", ID_REQUIRED).setValueAsBoolean(false)
            changes.add(marked)
            return
        }
        val application = root.getElement(AndroidManifest.TAG_application)
        val feature = if (application != null) {
            root.newElementAt(application.index, AndroidManifest.TAG_uses_feature)
        } else {
            root.newElement(AndroidManifest.TAG_uses_feature)
        }
        feature.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(featureName)
        feature.getOrCreateAndroidAttribute("required", ID_REQUIRED).setValueAsBoolean(false)
        changes.add(added)
    }

    private fun removeNavigationHost(manifest: AndroidManifestBlock) {
        val root = manifest.manifestElement ?: return
        val permissions = elementsNamed(root, "uses-permission").filter { element ->
            val name = AndroidManifestBlock.getAndroidNameValue(element)
            name == "androidx.car.app.NAVIGATION_TEMPLATES" || name == "androidx.car.app.ACCESS_SURFACE"
        }
        for (element in permissions) element.parentElement?.removeElementsIf { it === element }
        removeMeta(manifest, MIN_CAR_API, ArrayList(), null)
        removeMeta(manifest, GMS_CAR, ArrayList(), null)
        val application = manifest.applicationElement ?: return
        val services = elementsNamed(application, "service").filter { element ->
            AndroidManifestBlock.getAndroidNameValue(element)?.endsWith("NavigationCarService") == true
        }
        for (element in services) element.parentElement?.removeElementsIf { it === element }
    }

    private fun clearGameDeclaration(application: ResXmlElement, changes: MutableList<String>) {
        val category = findAttr(application, ID_APP_CATEGORY, "appCategory")
        val game = findAttr(application, ID_IS_GAME, "isGame")
        val wasGame = categoryName(category) == "game" || (game != null && isTrue(game))
        if (categoryName(category) == "game") application.removeAttributesWithName("appCategory")
        if (game != null && isTrue(game)) application.removeAttributesWithName("isGame")
        changes.add(
            if (wasGame) {
                "Removed the game declaration so Android Auto can list it as navigation."
            } else {
                "Left the app undeclared as a game, the same way FS Play is declared."
            },
        )
    }

    private fun ensurePermission(
        manifest: AndroidManifestBlock,
        name: String,
        changes: MutableList<String>,
        message: String,
    ) {
        val root = manifest.manifestElement ?: return
        val present = elementsNamed(root, "uses-permission").any { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == name
        }
        if (present) return
        root.newElement("uses-permission")
            .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(name)
        changes.add(message)
    }

    private fun ensureMinCarApi(parent: ResXmlElement, changes: MutableList<String>, message: String) {
        val existing = elementsNamed(parent, AndroidManifest.TAG_meta_data).firstOrNull { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == MIN_CAR_API
        }
        if (existing == null) {
            val meta = parent.newElement(AndroidManifest.TAG_meta_data)
            meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString(MIN_CAR_API)
            meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_value, AndroidManifest.ID_value)
                .setValueAsString("5")
            changes.add(message)
            return
        }
        val value = findAttr(existing, AndroidManifest.ID_value, AndroidManifest.NAME_value)
        if (value == null || value.valueAsString != "5") {
            existing.getOrCreateAndroidAttribute(AndroidManifest.NAME_value, AndroidManifest.ID_value)
                .setValueAsString("5")
            changes.add(message)
        }
    }

    private fun ensureNavigationService(
        manifest: AndroidManifestBlock,
        application: ResXmlElement,
        changes: MutableList<String>,
    ) {
        val serviceName = "com.automaker.nav.NavigationCarService"
        for (service in elementsNamed(application, "service")) {
            val name = AndroidManifestBlock.getAndroidNameValue(service)
            if (name != null && name.endsWith("NavigationCarService") && name != serviceName) {
                service.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                    .setValueAsString(serviceName)
            }
        }
        val existing = elementsNamed(application, "service").firstOrNull { service ->
            elementsNamed(service, AndroidManifest.TAG_intent_filter).any { filter ->
                hasNamedChild(filter, AndroidManifest.TAG_action, CAR_APP_SERVICE) &&
                    hasNamedChild(filter, AndroidManifest.TAG_category, CAR_NAVIGATION)
            }
        }
        if (existing != null) {
            ensureMinCarApi(existing, changes, "Set the navigation service car API level to 5.")
            changes.add("The navigation car service was already present.")
            return
        }
        val service = application.newElement("service")
        service.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(serviceName)
        service.getOrCreateAndroidAttribute(AndroidManifest.NAME_exported, AndroidManifest.ID_exported)
            .setValueAsBoolean(true)
        ensureMinCarApi(service, changes, "Added a navigation car service like FS Play.")
        val filter = service.newElement(AndroidManifest.TAG_intent_filter)
        filter.newElement(AndroidManifest.TAG_action)
            .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(CAR_APP_SERVICE)
        addCategory(filter, CAR_NAVIGATION)
    }

    private fun addCategory(filter: ResXmlElement, name: String) {
        filter.newElement(AndroidManifest.TAG_category)
            .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(name)
    }

    private fun ensureDistractionOptimized(manifest: AndroidManifestBlock, changes: MutableList<String>) {
        var changed = false
        for (activity in activities(manifest)) {
            if (!activityContains(activity, CAR_LAUNCHER)) continue
            val existing = elementsNamed(activity, AndroidManifest.TAG_meta_data).firstOrNull { element ->
                AndroidManifestBlock.getAndroidNameValue(element) == DISTRACTION
            }
            if (existing == null) {
                val meta = activity.newElement(AndroidManifest.TAG_meta_data)
                meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                    .setValueAsString(DISTRACTION)
                meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_value, AndroidManifest.ID_value)
                    .setValueAsString("true")
                changed = true
                continue
            }
            val value = findAttr(existing, AndroidManifest.ID_value, AndroidManifest.NAME_value)
            if (value == null || value.valueAsString != "true") {
                existing.getOrCreateAndroidAttribute(AndroidManifest.NAME_value, AndroidManifest.ID_value)
                    .setValueAsString("true")
                changed = true
            }
        }
        if (changed) {
            changes.add("Marked the car activity distraction-optimized so it can stay open while the car is moving.")
        } else {
            changes.add("The car activity was already distraction-optimized.")
        }
    }

    private fun ensureNavigationDescriptor(apk: ApkModule, application: ResXmlElement, changes: MutableList<String>) {
        val resourceId = navigationResourceId(apk)
        val existing = elementsNamed(application, AndroidManifest.TAG_meta_data).firstOrNull { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == GMS_CAR
        }
        if (existing == null) {
            val meta = application.newElement(AndroidManifest.TAG_meta_data)
            meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString(GMS_CAR)
            meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_resource, AndroidManifest.ID_resource)
                .setTypeAndData(ValueType.REFERENCE, resourceId)
            changes.add("Added Android Auto navigation metadata so the car can offer it while driving.")
            return
        }
        val resource = findAttr(existing, AndroidManifest.ID_resource, AndroidManifest.NAME_resource)
        if (resource == null || resource.valueType != ValueType.REFERENCE || resource.data != resourceId) {
            existing.getOrCreateAndroidAttribute(AndroidManifest.NAME_resource, AndroidManifest.ID_resource)
                .setTypeAndData(ValueType.REFERENCE, resourceId)
            changes.add("Added Android Auto navigation metadata so the car can offer it while driving.")
        } else {
            changes.add("Android Auto navigation metadata was already present.")
        }
    }

    private fun navigationResourceId(apk: ApkModule): Int {
        val entry = appPackage(apk).getOrCreate("", "xml", "automotive_app_desc")
        entry.setValueAsString(NAVIGATION_XML)
        val document = ResXmlDocument()
        val current = apk.getInputSource(NAVIGATION_XML)
        if (current != null) {
            try {
                current.openStream().use { document.readBytes(BlockReader(it)) }
            } catch (_: Exception) {
            }
        }
        val root = document.getOrCreateElement("automotiveApp")
        val present = elementsNamed(root, "uses").mapNotNull { element ->
            element.searchAttributeByName("name")?.valueAsString
        }.toSet()
        for (name in NAVIGATION_USES) {
            if (name in present) continue
            root.newElement("uses").getOrCreateAttribute("name", 0).setValueAsString(name)
        }
        document.refreshFull()
        apk.add(ByteInputSource(document.bytes, NAVIGATION_XML))
        if (entry.resourceId == 0) {
            throw IllegalStateException("The navigation descriptor could not be added to the package.")
        }
        return entry.resourceId
    }

    private fun appPackage(apk: ApkModule): PackageBlock {
        apk.ensureTableBlock()
        val table = apk.tableBlock
        val iterator = table.listPackages().iterator()
        if (iterator.hasNext()) return iterator.next()
        return table.newPackage(0x7f, "app")
    }

    private fun separateLauncherFromCar(manifest: AndroidManifestBlock) {
        for (activity in activities(manifest)) {
            for (filter in elementsNamed(activity, AndroidManifest.TAG_intent_filter)) {
                if (!hasNamedChild(filter, AndroidManifest.TAG_category, LAUNCHER)) continue
                val categories = elementsNamed(filter, AndroidManifest.TAG_category).filter { element ->
                    val name = AndroidManifestBlock.getAndroidNameValue(element)
                    name == CAR_LAUNCHER || name == APP_MAPS || name == CAR_NAVIGATION
                }
                for (element in categories) element.parentElement?.removeElementsIf { it === element }
            }
            if (!activityContains(activity, LAUNCHER)) continue
            val metas = elementsNamed(activity, AndroidManifest.TAG_meta_data).filter { element ->
                AndroidManifestBlock.getAndroidNameValue(element) == DISTRACTION
            }
            for (element in metas) element.parentElement?.removeElementsIf { it === element }
            val attribute = activity.searchAttributeByName(DISTRACTION)
            if (attribute != null && isTrue(attribute)) activity.removeAttribute(attribute)
        }
    }

    private fun ensureCarEntry(application: ResXmlElement) {
        val existing = elementsNamed(application, "activity").any { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == "android.app.Activity"
        }
        if (existing) return
        val activity = application.newElement("activity")
        activity.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString("android.app.Activity")
        activity.getOrCreateAndroidAttribute(AndroidManifest.NAME_exported, AndroidManifest.ID_exported)
            .setValueAsBoolean(true)
        val filter = activity.newElement(AndroidManifest.TAG_intent_filter)
        filter.newElement(AndroidManifest.TAG_action)
            .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(MAIN)
        addCategory(filter, CAR_LAUNCHER)
        addCategory(filter, CAR_NAVIGATION)
        addCategory(filter, APP_MAPS)
        val meta = activity.newElement(AndroidManifest.TAG_meta_data)
        meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(DISTRACTION)
        meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_value, AndroidManifest.ID_value)
            .setValueAsString("true")
    }

    private fun ensureCarLauncher(manifest: AndroidManifestBlock, changes: MutableList<String>, navigation: Boolean) {
        var added = 0
        var addedMaps = 0
        for (activity in activities(manifest)) {
            for (filter in elementsNamed(activity, AndroidManifest.TAG_intent_filter)) {
                if (!hasNamedChild(filter, AndroidManifest.TAG_category, LAUNCHER)) continue
                if (!hasNamedChild(filter, AndroidManifest.TAG_category, CAR_LAUNCHER)) {
                    filter.newElement(AndroidManifest.TAG_category)
                        .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                        .setValueAsString(CAR_LAUNCHER)
                    added += 1
                }
                if (navigation && !hasNamedChild(filter, AndroidManifest.TAG_category, APP_MAPS)) {
                    addCategory(filter, APP_MAPS)
                    addedMaps += 1
                }
                if (navigation && !hasNamedChild(filter, AndroidManifest.TAG_category, CAR_NAVIGATION)) {
                    addCategory(filter, CAR_NAVIGATION)
                    addedMaps += 1
                }
            }
        }
        if (added > 0) {
            changes.add(
                if (added == 1) {
                    "Added the Android Auto car launcher category to the main activity."
                } else {
                    "Added the Android Auto car launcher category to $added activities."
                },
            )
        } else if (activities(manifest).any { activityContains(it, CAR_LAUNCHER) }) {
            changes.add("The Android Auto car launcher category was already present.")
        } else {
            val first = activities(manifest).firstOrNull()
                ?: throw IllegalStateException("AndroidManifest.xml has no activity Android Auto can open.")
            val filter = first.newElement(AndroidManifest.TAG_intent_filter)
            filter.newElement(AndroidManifest.TAG_action)
                .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString(MAIN)
            filter.newElement(AndroidManifest.TAG_category)
                .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString(LAUNCHER)
            filter.newElement(AndroidManifest.TAG_category)
                .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString(CAR_LAUNCHER)
            if (navigation) {
                addCategory(filter, APP_MAPS)
                addCategory(filter, CAR_NAVIGATION)
                addedMaps += 2
            }
            changes.add("Added an Android Auto car launcher filter to the first activity.")
        }
        if (navigation) {
            changes.add(
                if (addedMaps > 0) {
                    "Added the navigation categories FS Play uses on its car activity."
                } else {
                    "The navigation categories were already present."
                },
            )
        }
    }

    private fun ensureExported(manifest: AndroidManifestBlock, changes: MutableList<String>) {
        var changed = false
        for (activity in activities(manifest)) {
            if (!activityContains(activity, CAR_LAUNCHER)) continue
            val exported = findAttr(activity, AndroidManifest.ID_exported, AndroidManifest.NAME_exported)
            if (exported != null && isTrue(exported)) continue
            activity.getOrCreateAndroidAttribute(AndroidManifest.NAME_exported, AndroidManifest.ID_exported)
                .setValueAsBoolean(true)
            changed = true
        }
        if (changed) changes.add("Marked the car activity exported so Android Auto can start it.")
    }

    private fun removeMeta(
        manifest: AndroidManifestBlock,
        name: String,
        changes: MutableList<String>,
        message: String?,
    ): Boolean {
        val found = ArrayList<ResXmlElement>()
        val iterator = manifest.recursiveElements()
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            if (element.equalsName(AndroidManifest.TAG_meta_data) && AndroidManifestBlock.getAndroidNameValue(element) == name) {
                found.add(element)
            }
        }
        for (element in found) {
            element.parentElement?.removeElementsIf { it === element }
        }
        if (found.isNotEmpty() && message != null) changes.add(message)
        return found.isNotEmpty()
    }

    private fun removeDistractionAttributes(manifest: AndroidManifestBlock): Boolean {
        var removed = false
        val elements = ArrayList<ResXmlElement>()
        val iterator = manifest.recursiveElements()
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            elements.add(element)
        }
        for (element in elements) {
            val attribute = element.searchAttributeByName(DISTRACTION) ?: continue
            if (!isTrue(attribute)) continue
            element.removeAttribute(attribute)
            removed = true
        }
        return removed
    }

    private fun activities(manifest: AndroidManifestBlock): List<ResXmlElement> {
        val list = ArrayList<ResXmlElement>()
        val iterator = manifest.getActivities(true)
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            list.add(element)
        }
        return list
    }

    private fun elementsNamed(parent: ResXmlElement, name: String): List<ResXmlElement> {
        val list = ArrayList<ResXmlElement>()
        val iterator = parent.getElements(name)
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            list.add(element)
        }
        return list
    }

    private fun hasNamedChild(parent: ResXmlElement, tag: String, value: String): Boolean {
        return elementsNamed(parent, tag).any { AndroidManifestBlock.getAndroidNameValue(it) == value }
    }

    private fun activityContains(activity: ResXmlElement, value: String): Boolean {
        return elementsNamed(activity, AndroidManifest.TAG_intent_filter).any { filter ->
            hasNamedChild(filter, AndroidManifest.TAG_category, value)
        }
    }

    private fun findAttr(element: ResXmlElement, id: Int, name: String) =
        element.searchAttributeByResourceId(id) ?: element.searchAttributeByName(name)

    private fun categoryName(attribute: com.reandroid.arsc.chunk.xml.ResXmlAttribute?): String? {
        if (attribute == null) return null
        if (attribute.valueType == ValueType.STRING) return attribute.valueAsString
        val index = attribute.data
        return CATEGORIES.getOrNull(index)
    }

    private fun isTrue(attribute: com.reandroid.arsc.chunk.xml.ResXmlAttribute): Boolean {
        return when (attribute.valueType) {
            ValueType.BOOLEAN -> attribute.valueAsBoolean
            ValueType.DEC, ValueType.HEX -> attribute.data != 0
            else -> attribute.valueAsString == "true"
        }
    }

    private fun isFalse(attribute: com.reandroid.arsc.chunk.xml.ResXmlAttribute): Boolean {
        return when (attribute.valueType) {
            ValueType.BOOLEAN -> !attribute.valueAsBoolean
            ValueType.DEC, ValueType.HEX -> attribute.data == 0
            else -> attribute.valueAsString == "false"
        }
    }
}
