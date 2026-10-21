const AUTOMOTIVE_FEATURE = "android.hardware.type.automotive";
const GAMEPAD_FEATURE = "android.hardware.gamepad";

function readTagEnd(xml, start) {
  let quote = null;
  for (let i = start; i < xml.length; i++) {
    const char = xml[i];
    if (quote) {
      if (char === quote) quote = null;
      continue;
    }
    if (char === '"' || char === "'") {
      quote = char;
      continue;
    }
    if (char === ">") return i;
  }
  return -1;
}

function findMatchingClose(xml, tag, contentStart) {
  let depth = 1;
  let i = contentStart;
  while (i < xml.length) {
    const lt = xml.indexOf("<", i);
    if (lt < 0) return -1;
    const end = readTagEnd(xml, lt);
    if (end < 0) return -1;
    const head = xml.slice(lt + 1, end).trim();
    const closing = head.startsWith("/");
    const name = (closing ? head.slice(1) : head).split(/[\s/]/)[0];
    const selfClosing = !closing && /\/\s*$/.test(head);
    if (name === tag) {
      if (closing) {
        depth -= 1;
        if (depth === 0) return lt;
      } else if (!selfClosing) {
        depth += 1;
      }
    }
    i = end + 1;
  }
  return -1;
}

function withActivities(xml, visitor) {
  const re = /<(activity-alias|activity)\b/g;
  let out = "";
  let last = 0;
  let match = re.exec(xml);
  while (match) {
    const tag = match[1];
    const start = match.index;
    const openEnd = readTagEnd(xml, start);
    if (openEnd < 0) break;
    const head = xml.slice(start, openEnd + 1);
    const selfClosing = /\/\s*>$/.test(head);
    let elementEnd;
    let inner = "";
    if (selfClosing) {
      elementEnd = openEnd + 1;
    } else {
      const closeAt = findMatchingClose(xml, tag, openEnd + 1);
      if (closeAt < 0) break;
      inner = xml.slice(openEnd + 1, closeAt);
      elementEnd = closeAt + `</${tag}>`.length;
    }
    const element = xml.slice(start, elementEnd);
    out += xml.slice(last, start);
    out += visitor({ tag, head, inner, selfClosing, element });
    last = elementEnd;
    re.lastIndex = elementEnd;
    match = re.exec(xml);
  }
  out += xml.slice(last);
  return out;
}

function replaceAttribute(tag, name, value) {
  const pattern = new RegExp(`android:${name}\\s*=\\s*("[^"]*"|'[^']*')`);
  if (pattern.test(tag)) {
    return { tag: tag.replace(pattern, `android:${name}="${value}"`), replaced: true };
  }
  return {
    tag: tag.replace(/(\s*\/?>)$/, ` android:${name}="${value}"$1`),
    replaced: false,
  };
}

function attributeValue(tag, name) {
  const match = tag.match(new RegExp(`android:${name}\\s*=\\s*"([^"]*)"`));
  return match ? match[1] : null;
}

function declareGame(xml, changes) {
  const match = /<application\b/.exec(xml);
  if (!match) throw new Error("AndroidManifest.xml has no <application> element.");
  const start = match.index;
  const end = readTagEnd(xml, start);
  if (end < 0) throw new Error("AndroidManifest.xml has an unclosed <application> tag.");

  let tag = xml.slice(start, end + 1);
  const previousCategory = attributeValue(tag, "appCategory");
  const category = replaceAttribute(tag, "appCategory", "game");
  tag = category.tag;
  const gameFlag = replaceAttribute(tag, "isGame", "true");
  tag = gameFlag.tag;

  if (previousCategory === "game" && attributeValue(xml.slice(start, end + 1), "isGame") === "true") {
    changes.push("The app was already declared as a game.");
  } else if (previousCategory && previousCategory !== "game") {
    changes.push(`Changed the app category from ${previousCategory} to game so Android Auto can show it while parked.`);
  } else {
    changes.push("Declared the app as a game so Android Auto can show its screen while parked.");
  }

  return xml.slice(0, start) + tag + xml.slice(end + 1);
}

function ensureOptionalFeature(xml, changes, feature, messages) {
  const pattern = new RegExp(`<uses-feature\\b[^>]*android:name="${feature}"[^>]*\\/?>`);
  const found = xml.match(pattern);
  if (found) {
    let tag = found[0];
    if (/android:required\s*=\s*"false"/.test(tag)) {
      changes.push(messages.already);
      return xml;
    }
    if (/android:required\s*=\s*"true"/.test(tag)) {
      tag = tag.replace(/android:required\s*=\s*"true"/, 'android:required="false"');
    } else {
      tag = tag.replace(/(\s*\/?>)$/, ' android:required="false"$1');
    }
    changes.push(messages.marked);
    return xml.replace(found[0], tag);
  }

  const line = `    <uses-feature android:name="${feature}" android:required="false"/>\n\n`;
  const index = xml.indexOf("<application");
  changes.push(messages.added);
  return xml.slice(0, index) + line + xml.slice(index);
}

function ensureAutomotiveFeature(xml, changes) {
  return ensureOptionalFeature(xml, changes, AUTOMOTIVE_FEATURE, {
    already: "Automotive hardware was already optional, so the APK still installs on a phone.",
    marked: "Marked automotive hardware as optional so the APK still installs on a phone.",
    added: "Declared optional automotive hardware so the same APK installs on a phone and in the car.",
  });
}

function ensureGamepadFeature(xml, changes) {
  return ensureOptionalFeature(xml, changes, GAMEPAD_FEATURE, {
    already: "Game controller support was already declared.",
    marked: "Marked game controller support as optional.",
    added: "Added game controller support.",
  });
}

function ensureCarLauncher(xml, changes, navigation) {
  let added = 0;
  let addedMaps = 0;
  const updated = xml.replace(/<intent-filter\b[\s\S]*?<\/intent-filter>/g, (block) => {
    const hasLauncher = /android\.intent\.category\.LAUNCHER"/.test(block);
    if (!hasLauncher) return block;
    let next = block;
    if (!/android\.intent\.category\.CAR_LAUNCHER"/.test(next)) {
      added += 1;
      next = next.replace(
        /<\/intent-filter>/,
        '    <category android:name="android.intent.category.CAR_LAUNCHER"/>\n        </intent-filter>',
      );
    }
    if (navigation && !/android\.intent\.category\.APP_MAPS"/.test(next)) {
      addedMaps += 1;
      next = next.replace(
        /<\/intent-filter>/,
        '    <category android:name="android.intent.category.APP_MAPS"/>\n        </intent-filter>',
      );
    }
    if (navigation && !/androidx\.car\.app\.category\.NAVIGATION"/.test(next)) {
      addedMaps += 1;
      next = next.replace(
        /<\/intent-filter>/,
        '    <category android:name="androidx.car.app.category.NAVIGATION"/>\n        </intent-filter>',
      );
    }
    return next;
  });

  let result = updated;
  if (added > 0) {
    changes.push(
      added === 1
        ? "Added the Android Auto car launcher category to the main activity."
        : `Added the Android Auto car launcher category to ${added} activities.`,
    );
  } else if (/android\.intent\.category\.CAR_LAUNCHER"/.test(result)) {
    changes.push("The Android Auto car launcher category was already present.");
  } else {

  let inserted = false;
  const withFilter = withActivities(updated, (activity) => {
    if (inserted) return activity.element;
    inserted = true;
    const filter = [
      "",
      "            <intent-filter>",
      '                <action android:name="android.intent.action.MAIN"/>',
      '                <category android:name="android.intent.category.LAUNCHER"/>',
      '                <category android:name="android.intent.category.CAR_LAUNCHER"/>',
      navigation ? '                <category android:name="android.intent.category.APP_MAPS"/>' : null,
      navigation ? '                <category android:name="androidx.car.app.category.NAVIGATION"/>' : null,
      "            </intent-filter>",
      "        ",
    ].filter((line) => line != null).join("\n");
    if (activity.selfClosing) {
      const open = activity.head.replace(/\/\s*>$/, ">");
      return `${open}${filter}</${activity.tag}>`;
    }
    return activity.head + filter + activity.inner + `</${activity.tag}>`;
  });

  if (!inserted) {
    throw new Error("AndroidManifest.xml has no activity Android Auto can open.");
  }
  changes.push("Added an Android Auto car launcher filter to the first activity.");
  if (navigation) addedMaps += 1;
  result = withFilter;
  }

  if (navigation) {
    changes.push(
      addedMaps > 0
        ? "Added the navigation categories FS Play uses on its car activity."
        : "The navigation categories were already present.",
    );
  }
  return result;
}

function ensureExported(xml, changes) {
  let changed = false;
  const next = withActivities(xml, (activity) => {
    if (!/android\.intent\.category\.CAR_LAUNCHER"/.test(activity.element)) return activity.element;
    if (/android:exported\s*=\s*"true"/.test(activity.head)) return activity.element;
    changed = true;
    const head = /android:exported\s*=\s*"(true|false)"/.test(activity.head)
      ? activity.head.replace(/android:exported\s*=\s*"(true|false)"/, 'android:exported="true"')
      : activity.head.replace(/(\s*\/?>)$/, ' android:exported="true"$1');
    if (activity.selfClosing) return head;
    return head + activity.inner + `</${activity.tag}>`;
  });
  if (changed) {
    changes.push("Marked the car activity exported so Android Auto can start it.");
  }
  return next;
}

function removeDistractionOptimized(xml, changes) {
  const before = xml;
  let next = xml.replace(
    /[ \t]*<meta-data\b[^>]*android:name="distractionOptimized"[^>]*\/>\s*/g,
    "\n",
  );
  next = next.replace(
    /[ \t]*<meta-data\b[^>]*android:name="distractionOptimized"[^>]*>[\s\S]*?<\/meta-data>\s*/g,
    "\n",
  );
  next = next.replace(/\s+android:distractionOptimized\s*=\s*("true"|'true')/g, "");
  if (next !== before) {
    changes.push("Removed distraction-optimized flags so Android Auto keeps the app in the parked category.");
  }
  return next;
}

function removeDerivedApkId(xml, changes) {
  const before = xml;
  let next = xml.replace(
    /[ \t]*<meta-data\b[^>]*android:name="com\.android\.vending\.derived\.apk\.id"[^>]*\/>\s*/g,
    "\n",
  );
  next = next.replace(
    /[ \t]*<meta-data\b[^>]*android:name="com\.android\.vending\.derived\.apk\.id"[^>]*>[\s\S]*?<\/meta-data>\s*/g,
    "\n",
  );
  if (next !== before) {
    changes.push("Removed the Play-derived APK id so the resigned package can be installed.");
  }
  return next;
}

function declareMaps(xml, changes) {
  const match = /<application\b/.exec(xml);
  if (!match) throw new Error("AndroidManifest.xml has no <application> element.");
  const start = match.index;
  const end = readTagEnd(xml, start);
  if (end < 0) throw new Error("AndroidManifest.xml has an unclosed <application> tag.");

  let tag = xml.slice(start, end + 1);
  const previousCategory = attributeValue(tag, "appCategory");
  tag = replaceAttribute(tag, "appCategory", "maps").tag;
  tag = replaceAttribute(tag, "isGame", "false").tag;

  if (previousCategory === "maps" && attributeValue(xml.slice(start, end + 1), "isGame") !== "true") {
    changes.push("The app was already declared as maps.");
  } else if (previousCategory && previousCategory !== "maps") {
    changes.push(`Changed the app category from ${previousCategory} to maps so Android Auto can treat it as navigation while driving.`);
  } else {
    changes.push("Declared the app as maps so Android Auto can treat it as navigation while driving.");
  }

  return xml.slice(0, start) + tag + xml.slice(end + 1);
}

function ensureDistractionOptimized(xml, changes) {
  let changed = false;
  const next = withActivities(xml, (activity) => {
    if (!/android\.intent\.category\.CAR_LAUNCHER"/.test(activity.element)) return activity.element;
    if (/android:name="distractionOptimized"[\s\S]*android:value="true"/.test(activity.element)) return activity.element;
    if (/android:distractionOptimized\s*=\s*"true"/.test(activity.head)) return activity.element;
    changed = true;
    if (/android:name="distractionOptimized"/.test(activity.element)) {
      return activity.element.replace(
        /(<meta-data\b[^>]*android:name="distractionOptimized"[^>]*android:value=")[^"]*(")/,
        "$1true$2",
      );
    }
    const meta = '\n            <meta-data android:name="distractionOptimized" android:value="true"/>';
    if (activity.selfClosing) {
      const open = activity.head.replace(/\/\s*>$/, ">");
      return `${open}${meta}\n        </${activity.tag}>`;
    }
    return activity.head + meta + activity.inner + `</${activity.tag}>`;
  });
  if (changed) {
    changes.push("Marked the car activity distraction-optimized so it can stay open while the car is moving.");
  } else if (/distractionOptimized/.test(next)) {
    changes.push("The car activity was already distraction-optimized.");
  }
  return next;
}

function ensureNavigationMeta(xml, changes) {
  const tags = xml.match(/<meta-data\b[^>]*\/?>/g) || [];
  const existing = tags.find((tag) => /android:name="com\.google\.android\.gms\.car\.application"/.test(tag));
  if (existing) {
    if (/android:resource="@xml\/[^"]+"/.test(existing)) {
      changes.push("Android Auto navigation metadata was already present.");
      return xml;
    }
    const replacement = existing.replace(/(\s*\/?>)$/, ' android:resource="@xml/automotive_app_desc"$1');
    changes.push("Added Android Auto navigation metadata so the car can offer it while driving.");
    return xml.replace(existing, replacement);
  }

  const match = /<application\b/.exec(xml);
  if (!match) throw new Error("AndroidManifest.xml has no <application> element.");
  const end = readTagEnd(xml, match.index);
  if (end < 0) throw new Error("AndroidManifest.xml has an unclosed <application> tag.");
  const line = '\n        <meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc"/>';
  changes.push("Added Android Auto navigation metadata so the car can offer it while driving.");
  return xml.slice(0, end + 1) + line + xml.slice(end + 1);
}

export function navigationDescriptorName(xml) {
  const tags = xml.match(/<meta-data\b[^>]*\/?>/g) || [];
  for (const tag of tags) {
    if (!/android:name="com\.google\.android\.gms\.car\.application"/.test(tag)) continue;
    const resource = tag.match(/android:resource="@xml\/([A-Za-z0-9_.]+)"/);
    if (resource) return resource[1];
  }
  return "automotive_app_desc";
}

function removeNavigationHost(xml) {
  let next = xml;
  next = next.replace(/[ \t]*<uses-permission\b[^>]*android:name="androidx\.car\.app\.NAVIGATION_TEMPLATES"[^>]*\/>\s*/g, "\n");
  next = next.replace(/[ \t]*<uses-permission\b[^>]*android:name="androidx\.car\.app\.ACCESS_SURFACE"[^>]*\/>\s*/g, "\n");
  next = next.replace(/[ \t]*<meta-data\b[^>]*android:name="androidx\.car\.app\.minCarApiLevel"[^>]*\/>\s*/g, "\n");
  next = next.replace(/[ \t]*<meta-data\b[^>]*android:name="com\.google\.android\.gms\.car\.application"[^>]*\/>\s*/g, "\n");
  next = next.replace(/[ \t]*<service\b[^>]*android:name="[^"]*NavigationCarService"[^>]*>[\s\S]*?<\/service>\s*/g, "\n");
  next = next.replace(/[ \t]*<service\b[^>]*android:name="[^"]*NavigationCarService"[^>]*\/>\s*/g, "\n");
  return next;
}

function separateLauncherFromCar(xml) {
  const stripped = xml.replace(/<intent-filter\b[\s\S]*?<\/intent-filter>/g, (block) => {
    if (!/android\.intent\.category\.LAUNCHER"/.test(block)) return block;
    return block
      .replace(/\s*<category\b[^>]*android:name="android\.intent\.category\.CAR_LAUNCHER"\s*\/>/g, "")
      .replace(/\s*<category\b[^>]*android:name="android\.intent\.category\.APP_MAPS"\s*\/>/g, "")
      .replace(/\s*<category\b[^>]*android:name="androidx\.car\.app\.category\.NAVIGATION"\s*\/>/g, "");
  });
  return withActivities(stripped, (activity) => {
    if (!/android\.intent\.category\.LAUNCHER"/.test(activity.element)) return activity.element;
    return activity.element
      .replace(/[ \t]*<meta-data\b[^>]*android:name="distractionOptimized"[^>]*\/>\s*/g, "\n")
      .replace(/\s+android:distractionOptimized\s*=\s*("true"|'true')/g, "");
  });
}

function ensureCarEntry(xml) {
  if (/<activity\b[^>]*android:name="android\.app\.Activity"[\s\S]*?<\/activity>/.test(xml)) return xml;
  const close = xml.lastIndexOf("</application>");
  if (close < 0) throw new Error("AndroidManifest.xml has no </application> element.");
  const block = `
        <activity android:name="android.app.Activity" android:exported="true" android:excludeFromRecents="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.CAR_LAUNCHER"/>
                <category android:name="androidx.car.app.category.NAVIGATION"/>
                <category android:name="android.intent.category.APP_MAPS"/>
            </intent-filter>
            <meta-data android:name="distractionOptimized" android:value="true"/>
        </activity>
`;
  return xml.slice(0, close) + block + xml.slice(close);
}

function clearGameDeclaration(xml, changes) {
  const match = /<application\b/.exec(xml);
  if (!match) throw new Error("AndroidManifest.xml has no <application> element.");
  const start = match.index;
  const end = readTagEnd(xml, start);
  if (end < 0) throw new Error("AndroidManifest.xml has an unclosed <application> tag.");
  let tag = xml.slice(start, end + 1);
  const category = attributeValue(tag, "appCategory");
  const game = attributeValue(tag, "isGame");
  if (category === "game") tag = tag.replace(/\s+android:appCategory\s*=\s*("[^"]*"|'[^']*')/, "");
  if (game === "true") tag = tag.replace(/\s+android:isGame\s*=\s*("[^"]*"|'[^']*')/, "");
  if (category === "game" || game === "true") {
    changes.push("Removed the game declaration so Android Auto can list it as navigation.");
  } else {
    changes.push("Left the app undeclared as a game, the same way FS Play is declared.");
  }
  return xml.slice(0, start) + tag + xml.slice(end + 1);
}

function ensureUsesPermission(xml, name, changes, message) {
  if (xml.includes(`android:name="${name}"`)) return xml;
  const index = xml.indexOf("<application");
  if (index < 0) throw new Error("AndroidManifest.xml has no <application> element.");
  changes.push(message);
  return `${xml.slice(0, index)}    <uses-permission android:name="${name}"/>\n${xml.slice(index)}`;
}

function ensureMinCarApiLevel(xml, changes) {
  if (/android:name="androidx\.car\.app\.minCarApiLevel"[^>]*android:value="5"/.test(xml)) {
    changes.push("The car API level was already set to 5.");
    return xml;
  }
  if (/android:name="androidx\.car\.app\.minCarApiLevel"/.test(xml)) {
    changes.push("Set the car API level to 5, matching FS Play.");
    return xml.replace(
      /(<meta-data\b[^>]*android:name="androidx\.car\.app\.minCarApiLevel"[^>]*android:value=")[^"]*(")/,
      "$15$2",
    );
  }
  const match = /<application\b/.exec(xml);
  if (!match) throw new Error("AndroidManifest.xml has no <application> element.");
  const end = readTagEnd(xml, match.index);
  if (end < 0) throw new Error("AndroidManifest.xml has an unclosed <application> tag.");
  changes.push("Set the car API level to 5, matching FS Play.");
  const line = '\n        <meta-data android:name="androidx.car.app.minCarApiLevel" android:value="5"/>';
  return xml.slice(0, end + 1) + line + xml.slice(end + 1);
}

const NAV_SERVICE = "com.automaker.nav.NavigationCarService";

function ensureNavigationService(xml, changes) {
  let next = xml.replace(/android:name="[^"]*NavigationCarService"/g, `android:name="${NAV_SERVICE}"`);
  if (/androidx\.car\.app\.CarAppService"[\s\S]*?androidx\.car\.app\.category\.NAVIGATION"/.test(next)) {
    return next;
  }
  const close = next.lastIndexOf("</application>");
  if (close < 0) throw new Error("AndroidManifest.xml has no </application> element.");
  changes.push("Added a navigation car service like FS Play.");
  const block = `
        <service android:name="${NAV_SERVICE}" android:exported="true">
            <meta-data android:name="androidx.car.app.minCarApiLevel" android:value="5"/>
            <intent-filter>
                <action android:name="androidx.car.app.CarAppService"/>
                <category android:name="androidx.car.app.category.NAVIGATION"/>
            </intent-filter>
        </service>
`;
  return next.slice(0, close) + block + next.slice(close);
}

export function patchManifest(xml, kind = "game") {
  const navigation = kind === "navigation";
  const changes = [];
  let next = xml.replace(/^\uFEFF/, "");
  next = removeDerivedApkId(next, changes);
  if (navigation) {
    next = clearGameDeclaration(next, changes);
  } else {
    next = removeDistractionOptimized(next, changes);
    next = declareGame(next, changes);
  }
  next = ensureAutomotiveFeature(next, changes);
  next = ensureGamepadFeature(next, changes);
  next = ensureCarLauncher(next, changes, navigation);
  next = ensureExported(next, changes);
  if (navigation) {
    next = ensureDistractionOptimized(next, changes);
    next = ensureUsesPermission(next, "androidx.car.app.NAVIGATION_TEMPLATES", changes, "Added the navigation templates permission.");
    next = ensureUsesPermission(next, "androidx.car.app.ACCESS_SURFACE", changes, "Added the car surface permission.");
    next = ensureMinCarApiLevel(next, changes);
    next = ensureNavigationMeta(next, changes);
    next = ensureNavigationService(next, changes);
  }
  const packageMatch = next.match(/\bpackage="([^"]+)"/);
  return {
    xml: next,
    changes: [navigation ? "Prepared as navigation." : "Prepared as a parked game."],
    packageName: packageMatch ? packageMatch[1] : "",
    kind: navigation ? "navigation" : "game",
  };
}
