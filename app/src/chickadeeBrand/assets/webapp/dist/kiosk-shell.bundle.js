// DASHIE-BUNDLE-BUILD e2cdd6dc0 2026-08-25T23:23:02.947Z
window.__DASHIE_KIOSK_BUILDS=window.__DASHIE_KIOSK_BUILDS||{};window.__DASHIE_KIOSK_BUILDS["kiosk-shell.bundle.js"]="e2cdd6dc0 2026-08-25T23:23:02.947Z";

var O=(n,e)=>()=>(n&&(e=n(n=0)),e);var Xe=(n,e)=>()=>(e||n((e={exports:{}}).exports,e),e.exports);function pe(){if(_=document.getElementById("dashie-overlay"),!_){console.warn("[KioskShell] Overlay iframe not found");return}window.addEventListener("message",Ze),window.overlayHasKeyboardFocus=!1,window.handleRemoteInput=ot,window.forwardKeyToOverlay=at,ge(),console.log("[KioskShell] Overlay bridge initialized")}function ge(){K&&clearTimeout(K),K=setTimeout(()=>{!ee&&V<2?(V++,console.warn(`[KioskShell] No overlay ready signal after 15000ms \u2014 retrying (${V}/2)`),_.src=_.src.split("?")[0]+"?retry="+V,ge()):ee||console.error("[KioskShell] Overlay failed to load after 2 retries")},15e3)}function Ze(n){if(!_||n.source!==_.contentWindow)return;let e=n.data;if(!(!e||e.source!=="dashie-overlay"))switch(e.type){case"ready":Qe();break;case"pointer-events":_.style.pointerEvents=e.enable?"auto":"none",e.enable&&_.focus();break;case"close-sidebar":typeof DashieNative<"u"&&DashieNative.closeSidebar&&DashieNative.closeSidebar();break;case"keyboard-focus":if(window._overlayWantsKeys=e.capture,window._shellSettingsDpad)break;window.overlayHasKeyboardFocus=e.capture||!!window.dashieDashBarDpad,typeof DashieNative<"u"&&DashieNative.setOverlayKeyboardFocus&&DashieNative.setOverlayKeyboardFocus(e.capture||!!window.dashieDashBarDpad);break;case"forward-click":typeof DashieNative<"u"&&DashieNative.injectTouch&&DashieNative.injectTouch(e.x,e.y);break;case"timer-created":typeof DashieNative<"u"&&DashieNative.onTimerCreated&&DashieNative.onTimerCreated(JSON.stringify(e.timer));break;case"timer-updated":typeof DashieNative<"u"&&DashieNative.onTimerUpdated&&DashieNative.onTimerUpdated(JSON.stringify(e.timer));break;case"timer-completed":typeof DashieNative<"u"&&DashieNative.onTimerCompleted&&DashieNative.onTimerCompleted(JSON.stringify(e.timer));break;case"timer-cancelled":typeof DashieNative<"u"&&DashieNative.onTimerCancelled&&DashieNative.onTimerCancelled(e.timerId);break}}function Qe(){console.log("[KioskShell] Overlay ready"),ee=!0,window.dashieOverlayReady=!0,K&&(clearTimeout(K),K=null),et(),tt()}function x(n,e){_?.contentWindow&&_.contentWindow.postMessage({source:"dashie-parent",type:"screensaver-call",method:n,args:e||[]},"*")}function et(){window.screensaver={activate:()=>x("activate"),deactivate:()=>x("deactivate"),showPhoto:(n,e,t)=>x("showPhoto",[n,e,t]),updateMetadata:(n,e,t)=>x("updateMetadata",[n,e,t]),configure:(n,e,t,o)=>x("configure",[n,e,t,o]),setPhotos:n=>x("setPhotos",[n]),setAuthToken:n=>x("setAuthToken",[n]),showThumbnail:n=>x("showThumbnail",[n]),hideThumbnail:()=>x("hideThumbnail"),openGallery:n=>x("openGallery",[n])}}function tt(){window.dashieOverlay={openDrawer:()=>{_.style.pointerEvents="auto",_.contentWindow?.postMessage({source:"dashie-parent",type:"open-drawer"},"*")},closeDrawer:()=>{_.contentWindow?.postMessage({source:"dashie-parent",type:"close-drawer"},"*")},forwardKey:n=>{_.contentWindow?.postMessage({source:"dashie-parent",type:"remote-input",keyCode:n},"*")}}}function ot(n){let e=nt[n]||n;if(window.dashieOnboardingDpad){console.log(`[DpadRoute] ${e} \u2192 onboarding`),window.dashieOnboardingDpad(n);return}if(window._ccOverlayDpad){console.log(`[DpadRoute] ${e} \u2192 ccOverlay`),window._ccOverlayDpad(n);return}if(window._shellSettingsDpad){console.log(`[DpadRoute] ${e} \u2192 shellSettings`),window._shellSettingsDpad(n);return}if(window._overlayWantsKeys&&_?.contentWindow){console.log(`[DpadRoute] ${e} \u2192 overlayIframe`),_.contentWindow.postMessage({source:"dashie-parent",type:"remote-input",keyCode:n},"*");return}if(window.dashieDashBarDpad){console.log(`[DpadRoute] ${e} \u2192 dashBar`),window.dashieDashBarDpad(n);return}if(window.overlayHasKeyboardFocus){console.log(`[DpadRoute] ${e} \u2192 overlayFocus`),_?.contentWindow?.postMessage({source:"dashie-parent",type:"remote-input",keyCode:n},"*");return}if((n===4||n===21)&&window.dashieHandleBack){console.log(`[DpadRoute] ${e} \u2192 handleBack`),window.dashieHandleBack();return}console.log(`[DpadRoute] ${e} \u2192 unhandled (passthrough to WebView)`)}function at(n){_?.contentWindow?.postMessage({source:"dashie-parent",type:"remote-input",keyCode:n},"*")}var _,ee,V,K,nt,fe=O(()=>{_=null,ee=!1,V=0,K=null;nt={4:"BACK",19:"UP",20:"DOWN",21:"LEFT",22:"RIGHT",23:"CENTER",66:"ENTER"}});function it(){let n;try{let e=window.DashieNative?.getEdition;if(typeof e!="function")return console.warn(te,'DROP: [expected] DashieNative.getEdition unavailable \u2014 browser dev session or an APK predating it. Assuming "'+M+'".'),M;n=e.call(window.DashieNative)}catch(e){return console.warn(te,'DROP: [expected] DashieNative.getEdition threw \u2014 assuming "'+M+'".',e),M}return n&&Object.prototype.hasOwnProperty.call(oe,n)?n:(console.warn(te,'DROP: [unexpected] edition "'+n+'" is not in the brand table. Falling back to "'+M+'", which is WRONG for any non-Dashie edition \u2014 the user will see Dashie branding. Add it to kiosk-overlay/js/brand.js and to JS_KOTLIN_CONTRACTS #64.'),M)}function H(){return ne||(ne=oe[it()]),ne}function be(n){n&&n.style.setProperty("--accent-primary",H().accent)}var te,me,oe,M,ne,yt,z=O(()=>{te="[Brand]",me="#FF9500",oe={dashie:{edition:"dashie",name:"Dashie",logo:"artwork/Dashie_Full_Logo_Orange_Transparent.png",sidebarLogo:{dark:"images/dashie-logo-orange.png",light:"images/dashie-logo-orange.png"},accent:me,hasAccounts:!0,legal:{privacyUrl:"https://dashieapp.com/privacy-policy.html",termsUrl:"https://dashieapp.com/terms-of-service.html"}},chickadee:{edition:"chickadee",name:"Chickadee",logo:"artwork/Chickadee_Full_Logo_White_Transparent.png",sidebarLogo:{dark:"artwork/Chickadee_Full_Logo_White_Transparent.png",light:"artwork/Chickadee_Full_Logo_Charcoal_Transparent.png"},accent:me,hasAccounts:!1,legal:null}},M="dashie",ne=null;yt=Object.keys(oe)});function ye(n,e=""){let t=e?`onboarding-logo ${e}`:"onboarding-logo";return`<img src="${n.logo}" alt="${D(n.name)}" class="${t}" />`}function we({message:n="Setting things up..."}={}){let e=H(),t=document.createElement("div");return t.className="onboarding-card onboarding-card--welcome",t.innerHTML=`
    <div class="onboarding-welcome-main" style="align-items: center; justify-content: center; gap: 20px;">
      ${ye(e)}
      <div class="onboarding-spinner" style="width: 48px; height: 48px;"></div>
      <div class="onboarding-subtitle">${D(n)}</div>
    </div>
  `,t}function Y(n){if(!n)return"";try{let e=new URL(n);return e.port?`${e.hostname}:${e.port}`:e.hostname}catch{return String(n).replace(/^https?:\/\//,"").replace(/\/+$/,"")}}function Se(n,e){let t=Y(n).split(":")[0],o=Y(e).split(":")[0];return!!t&&t===o}function De({scanResults:n,scanning:e,configuredHa:t,onHaPath:o,onStandalonePath:a,onCustomUrlPath:i,onCreateAccount:l,onSignIn:d,onClose:c}){let h=H(),r=document.createElement("div");r.className="onboarding-card onboarding-card--welcome";let s=n?.ha?.[0]||null,p=!e&&s,u=t?.url||"",v=Y(u),m=s?`${s.ip}:${s.port}`:"",f=!!(v&&m&&!Se(u,s?.url||m)),S="";if(e)S='<div class="onboarding-scan-status"><div class="onboarding-spinner"></div> Scanning your network...</div>';else if(v){let G=f?`<div class="onboarding-scan-alt">Also found ${D(m)} on your network \u2014 a different box.</div>`:"";S=`<div class="onboarding-scan-status onboarding-scan-found-box">Using your configured Home Assistant at ${D(v)}.</div>${G}`}else p&&(S=`<div class="onboarding-scan-status onboarding-scan-found-box">Found a Home Assistant on your network at ${D(m)}.</div>`);let C=u||s?.url||null,w=!e&&!s&&!u,P=(()=>{try{let G=window.DashieNative?.isDashieAccountEnabled;return typeof G=="function"?!!G.call(window.DashieNative):!0}catch{return!0}})(),I=h.hasAccounts&&P,T;w?I?T=`
        <button class="onboarding-path-btn primary" id="ob-signin-btn">
          <span class="onboarding-path-icon">${st}</span>
          <span class="onboarding-path-text">
            <span class="onboarding-path-label">Sign in with Google</span>
          </span>
        </button>

        <div class="onboarding-divider"><span>or</span></div>

        <button class="onboarding-path-btn secondary" id="ob-create-btn">
          <span class="onboarding-path-icon onboarding-path-icon--grid">${ve}</span>
          <span class="onboarding-path-text">
            <span class="onboarding-path-label">Sign up for ${D(h.name)}</span>
            <span class="onboarding-path-desc">Calendar, photos, family sharing, and more</span>
          </span>
        </button>

        <div class="onboarding-divider"><span>or</span></div>

        <button class="onboarding-path-btn secondary" id="ob-ha-btn">
          <span class="onboarding-path-icon">${ae}</span>
          <span class="onboarding-path-text">
            <span class="onboarding-path-label">Use ${D(h.name)} with Home Assistant</span>
            <span class="onboarding-path-desc">Connect manually if Home Assistant isn't on this network</span>
          </span>
        </button>
      `:T=`
        <button class="onboarding-path-btn primary" id="ob-ha-btn">
          <span class="onboarding-path-icon">${ae}</span>
          <span class="onboarding-path-text">
            <span class="onboarding-path-label">Connect to Home Assistant</span>
            <span class="onboarding-path-desc">Enter your Home Assistant address manually</span>
          </span>
        </button>
      `:(T=`
      <button class="onboarding-path-btn primary" id="ob-ha-btn">
        <span class="onboarding-path-icon">${ae}</span>
        <span class="onboarding-path-text">
          <span class="onboarding-path-label">${C?"Configure Home Assistant":"Connect to Home Assistant"} or Host Your Own Dashboard</span>
          <span class="onboarding-path-desc">Display dashboards & enable voice control for a smarter smart home</span>
        </span>
      </button>
    `,I&&(T+=`
        <div class="onboarding-divider"><span>or</span></div>

        <button class="onboarding-path-btn secondary" id="ob-standalone-btn">
          <span class="onboarding-path-icon onboarding-path-icon--grid">${ve}</span>
          <span class="onboarding-path-text">
            <span class="onboarding-path-label">Use ${D(h.name)} without Home Assistant</span>
            <span class="onboarding-path-desc">Calendar, photos, family sharing, and more</span>
          </span>
        </button>
      `));let R=h.legal?`
    <div class="onboarding-footer">
      <div class="onboarding-legal-links">
        <a href="${h.legal.privacyUrl}" target="_blank" rel="noopener noreferrer">Privacy Policy</a>
        <span class="onboarding-legal-sep">&bull;</span>
        <a href="${h.legal.termsUrl}" target="_blank" rel="noopener noreferrer">Terms of Service</a>
      </div>
    </div>
  `:"";r.innerHTML=`
    <button class="onboarding-close-btn" id="ob-close-btn" aria-label="Close">&times;</button>
    <div class="onboarding-welcome-main">
      <!-- No "Welcome to <brand>!" line here (John, 2026-08-05): the mark IS the greeting, at
           double size. The alt text still carries the brand name, so a screen reader announces
           the card exactly as before \u2014 dropping the heading must not drop the only accessible
           statement of what this app is. -->
      ${ye(h,"onboarding-logo--hero")}
      ${S}

      <div class="onboarding-path-buttons">
        ${T}
      </div>
    </div>

    ${R}
  `;let $=r.querySelector("#ob-ha-btn");$&&$.addEventListener("click",()=>o(C));let b=r.querySelector("#ob-standalone-btn");b&&b.addEventListener("click",()=>a&&a());let E=r.querySelector("#ob-custom-url-btn");E&&E.addEventListener("click",()=>i&&i());let F=r.querySelector("#ob-create-btn");F&&F.addEventListener("click",()=>l&&l());let B=r.querySelector("#ob-signin-btn");return B&&B.addEventListener("click",()=>d&&d()),r.querySelector("#ob-close-btn").addEventListener("click",()=>{c&&c()}),r}function _e({prefilledUrl:n,savedConfig:e,onConnect:t,onBack:o,onCustomUrl:a}){let i=document.createElement("div");i.className="onboarding-card";let l=e?.url||n||"",d=e?.dashboardPath||"",c=e?e.hideSidebar:!0,h=e?e.hideTabs:!1,r=e?e.apiEnabled:!1,s=e?.apiPort||2323,p=e?.apiPassword||"",u=e?e.autoBuild!==!1:!0,m=!!n?e?.url&&!Se(e.url,n)?`<span class="onboarding-detected-badge onboarding-detected-badge--mismatch">Scan found a different box at ${D(Y(n))} \u2014 the address above is the one you configured</span>`:`<span class="onboarding-detected-badge">Detected at ${D(n)}</span>`:"";i.innerHTML=`
    <div class="onboarding-ha-config-header">
      <div class="onboarding-ha-logo-inline">${rt}</div>
      <div class="onboarding-title" style="margin-bottom: 0;">Configure Home Assistant</div>
    </div>

    <!-- Auto-Build URL toggle row -->
    <div class="onboarding-auto-build-row">
      <input type="checkbox" class="onboarding-toggle" id="ob-auto-build" ${u?"checked":""} />
      <span class="onboarding-toggle-label">Auto-Build URL</span>
      ${m}
    </div>

    <!-- Auto-Build fields (base URL + dashboard path side by side) -->
    <div id="ob-auto-build-section" style="${u?"":"display: none;"}">
      <div class="onboarding-url-row">
        <div class="onboarding-form-group onboarding-url-base">
          <label class="onboarding-form-label">Base URL</label>
          <input class="onboarding-input" id="ob-ha-base-url" type="url"
                 placeholder="192.168.1.100:8123"
                 value="${l}" autocomplete="off" />
        </div>
        <div class="onboarding-form-group onboarding-url-path">
          <label class="onboarding-form-label">Dashboard Path</label>
          <input class="onboarding-input" id="ob-ha-dashboard" type="text"
                 placeholder="e.g. kitchen" value="${d}"
                 autocomplete="off" autocapitalize="off" />
        </div>
      </div>
    </div>

    <!-- Manual URL field (single full URL) -->
    <div id="ob-manual-url-section" style="${u?"display: none;":""}">
      <div class="onboarding-form-group">
        <label class="onboarding-form-label">Full Dashboard URL</label>
        <div class="onboarding-form-hint">You can change this later in Settings.</div>
        <input class="onboarding-input" id="ob-ha-full-url" type="url"
               placeholder="http://192.168.1.100:8123/lovelace/0"
               value="" autocomplete="off" />
      </div>
    </div>

    <div class="onboarding-toggle-row">
      <span class="onboarding-toggle-label">Hide sidebar</span>
      <input type="checkbox" class="onboarding-toggle" id="ob-hide-sidebar" ${c?"checked":""} />
    </div>
    <div class="onboarding-toggle-row">
      <span class="onboarding-toggle-label">Hide header / tabs</span>
      <input type="checkbox" class="onboarding-toggle" id="ob-hide-tabs" ${h?"checked":""} />
    </div>

    <div class="onboarding-toggle-row" style="border-bottom: none;">
      <span class="onboarding-toggle-label">Enable API</span>
      <input type="checkbox" class="onboarding-toggle" id="ob-api-enabled" ${r?"checked":""} />
    </div>
    <div id="ob-api-details" style="${r?"":"display: none;"}">
      <div class="onboarding-url-row">
        <div class="onboarding-form-group onboarding-url-base">
          <label class="onboarding-form-label">API Password</label>
          <input class="onboarding-input" id="ob-api-password" type="password"
                 placeholder="Optional" value="${p}" autocomplete="new-password" />
        </div>
        <div class="onboarding-form-group onboarding-url-path">
          <label class="onboarding-form-label">API Port</label>
          <input class="onboarding-input" id="ob-api-port" type="number"
                 value="${s}" min="1024" max="65535" />
        </div>
      </div>
    </div>
  `;let f=i.querySelector("#ob-auto-build"),S=i.querySelector("#ob-auto-build-section"),C=i.querySelector("#ob-manual-url-section");f.addEventListener("change",()=>{let b=f.checked;S.style.display=b?"":"none",C.style.display=b?"none":""});let w=i.querySelector("#ob-api-enabled"),P=i.querySelector("#ob-api-details");w.addEventListener("change",()=>{P.style.display=w.checked?"":"none",w.checked&&requestAnimationFrame(()=>{P.scrollIntoView({block:"nearest",behavior:"smooth"}),P.querySelectorAll("input").forEach(b=>{b.readOnly=!0}),document.activeElement&&document.activeElement!==w&&document.activeElement.blur&&document.activeElement.blur(),w.focus()})});let I=i.querySelector("#ob-ha-dashboard");I.addEventListener("keydown",b=>{b.key==="Enter"&&(b.preventDefault(),I.blur())});let T=document.createElement("div");T.className="onboarding-btn-row";let R=document.createElement("button");R.className="onboarding-btn-back",R.textContent="Back",R.addEventListener("click",o);let $=document.createElement("button");if($.className="onboarding-btn-primary",$.textContent="Connect",$.addEventListener("click",()=>{let b=f.checked,E,F;if(b){if(E=i.querySelector("#ob-ha-base-url").value.trim(),F=i.querySelector("#ob-ha-dashboard").value.trim(),!E){i.querySelector("#ob-ha-base-url").focus();return}}else if(E=i.querySelector("#ob-ha-full-url").value.trim(),F="",!E){i.querySelector("#ob-ha-full-url").focus();return}E=xe(E);try{let B=new URL(E);if(!B.hostname||!B.protocol.startsWith("http"))throw new Error("Invalid URL")}catch{He(i,b?"#ob-ha-base-url":"#ob-ha-full-url","Invalid URL \u2014 check the format (e.g. http://192.168.1.100:8123)");return}t({url:E,dashboardPath:F,autoBuild:b,hideSidebar:i.querySelector("#ob-hide-sidebar").checked,hideTabs:i.querySelector("#ob-hide-tabs").checked,apiEnabled:w.checked,apiPort:parseInt(i.querySelector("#ob-api-port").value)||2323,apiPassword:i.querySelector("#ob-api-password").value})}),T.appendChild(R),T.appendChild($),i.appendChild(T),a){let b=document.createElement("button");b.type="button",b.className="onboarding-alt-path",b.id="ob-host-other-btn",b.textContent="Host a different dashboard instead \u2192",b.addEventListener("click",()=>a()),i.appendChild(b)}return i.querySelectorAll("input.onboarding-input").forEach(b=>{b.readOnly=!0,b.addEventListener("click",()=>{b.readOnly=!1,b.focus()})}),i}function ke({savedConfig:n,onConnect:e,onBack:t}){let o=document.createElement("div");o.className="onboarding-card";let a=n?.url||"",i=n?n.apiEnabled:!1,l=n?.apiPort||2323,d=n?.apiPassword||"";o.innerHTML=`
    <div class="onboarding-title">Use another dashboard</div>
    <div class="onboarding-subtitle">Enter the address of the page you want to display.</div>

    <div class="onboarding-form-group">
      <label class="onboarding-form-label">Dashboard URL</label>
      <div class="onboarding-form-hint">You can change this later in Settings.</div>
      <input class="onboarding-input" id="ob-custom-full-url" type="url"
             placeholder="https://example.com/dashboard"
             value="${D(a)}" autocomplete="off" />
    </div>

    <div class="onboarding-toggle-row" style="border-bottom: none;">
      <span class="onboarding-toggle-label">Enable API</span>
      <input type="checkbox" class="onboarding-toggle" id="ob-api-enabled" ${i?"checked":""} />
    </div>
    <div id="ob-api-details" style="${i?"":"display: none;"}">
      <div class="onboarding-url-row">
        <div class="onboarding-form-group onboarding-url-base">
          <label class="onboarding-form-label">API Password</label>
          <input class="onboarding-input" id="ob-api-password" type="password"
                 placeholder="Optional" value="${D(d)}" autocomplete="new-password" />
        </div>
        <div class="onboarding-form-group onboarding-url-path">
          <label class="onboarding-form-label">API Port</label>
          <input class="onboarding-input" id="ob-api-port" type="number"
                 value="${l}" min="1024" max="65535" />
        </div>
      </div>
    </div>
  `;let c=o.querySelector("#ob-api-enabled"),h=o.querySelector("#ob-api-details");c.addEventListener("change",()=>{h.style.display=c.checked?"":"none",c.checked&&requestAnimationFrame(()=>{h.scrollIntoView({block:"nearest",behavior:"smooth"}),h.querySelectorAll("input").forEach(u=>{u.readOnly=!0}),document.activeElement&&document.activeElement!==c&&document.activeElement.blur&&document.activeElement.blur()})});let r=document.createElement("div");r.className="onboarding-btn-row";let s=document.createElement("button");s.className="onboarding-btn-back",s.id="ob-custom-back-btn",s.textContent="Back",s.addEventListener("click",()=>t&&t());let p=document.createElement("button");return p.className="onboarding-btn-primary",p.id="ob-custom-connect-btn",p.textContent="Continue",p.addEventListener("click",()=>{let u=o.querySelector("#ob-custom-full-url"),v=xe(u.value.trim());if(!v){u.focus();return}try{let m=new URL(v);if(!m.hostname||!m.protocol.startsWith("http"))throw new Error("Invalid URL")}catch{He(o,"#ob-custom-full-url","Invalid URL \u2014 check the format (e.g. https://example.com/dashboard)");return}e({url:v,apiEnabled:c.checked,apiPort:parseInt(o.querySelector("#ob-api-port").value)||2323,apiPassword:o.querySelector("#ob-api-password").value})}),r.appendChild(s),r.appendChild(p),o.appendChild(r),o.querySelectorAll("input.onboarding-input").forEach(u=>{u.readOnly=!0,u.addEventListener("click",()=>{u.readOnly=!1,u.focus()})}),o}function Ce({onGrantNow:n,onLater:e}){let t=H(),o=document.createElement("div");o.className="onboarding-card";let a=!1;try{if(typeof DashieNative<"u"&&DashieNative.getDeviceInfo){let r=JSON.parse(DashieNative.getDeviceInfo());a=r.isTv||r.isFireTV||!1}}catch{}let i=["Camera \u2014 motion detection & video streaming","Microphone \u2014 voice commands & video streaming",...a?[]:["Screen Off \u2014 enable screen to power off (device admin)"],"Exact Timers \u2014 reliable sleep and wake times","Battery Optimization \u2014 keep running in the background without being paused",...a?[]:["Brightness \u2014 allow control of screen brightness (change system settings)"],"Auto-Relaunch \u2014 restart app after forced shutdown (appear on top)"];o.innerHTML=`
    <div class="onboarding-title">Device Permissions</div>
    <div class="onboarding-subtitle">${D(t.name)} works best with a few permissions for voice control, screen management, and timers.</div>

    <ul class="onboarding-perm-summary">
      ${i.map(r=>`<li>${r}</li>`).join(`
      `)}
    </ul>
  `;let l=document.createElement("div");l.className="onboarding-btn-row";let d=document.createElement("button");d.className="onboarding-btn-back",d.textContent="Later",d.addEventListener("click",e);let c=document.createElement("button");c.className="onboarding-btn-primary",c.textContent="Set Up Now",c.addEventListener("click",n),l.appendChild(d),l.appendChild(c),o.appendChild(l);let h=document.createElement("div");return h.className="onboarding-tip",h.textContent="You can grant permissions later in Settings",o.appendChild(h),o}function Ee({onContinue:n,onLearnMore:e}){let t=H(),o=document.createElement("div");o.className="onboarding-card";let a=t.legal?`<a href="${t.legal.privacyUrl}" target="_blank" rel="noopener noreferrer">Privacy Policy</a>
      <span class="onboarding-data-links-sep">&bull;</span>`:"";o.innerHTML=`
    <div class="onboarding-title">Data Collection</div>
    <div class="onboarding-subtitle">Help improve ${D(t.name)} by sharing anonymous usage data. You can change these settings at any time.</div>

    <div class="onboarding-optout-row">
      <input type="checkbox" class="onboarding-checkbox" id="ob-perf-checkbox" checked>
      <div>
        <div class="onboarding-optout-text">Share Performance Data</div>
        <div class="onboarding-optout-desc">Anonymous metrics to help us fix crashes and improve stability</div>
      </div>
    </div>

    <div class="onboarding-optout-row">
      <input type="checkbox" class="onboarding-checkbox" id="ob-wake-checkbox" checked>
      <div>
        <div class="onboarding-optout-text">Share Wake Word Samples</div>
        <div class="onboarding-optout-desc">Short audio clips to improve voice detection accuracy</div>
      </div>
    </div>

    <div class="onboarding-data-links">
      ${a}
      <a href="#" id="ob-learn-more">Learn how we use your data to improve ${D(t.name)}</a>
    </div>
  `;let i=document.createElement("button");i.className="onboarding-btn-primary",i.textContent="Continue",i.addEventListener("click",()=>{let d=o.querySelector("#ob-perf-checkbox").checked,c=o.querySelector("#ob-wake-checkbox").checked;n({perfEnabled:d,wakeEnabled:c})}),o.appendChild(i);let l=document.createElement("div");return l.className="onboarding-tip",l.textContent="You can change these anytime in Settings",o.appendChild(l),o.querySelector("#ob-learn-more").addEventListener("click",d=>{d.preventDefault(),e()}),o}function Ne({onBack:n}){let e=H(),t=document.createElement("div");t.className="onboarding-card",t.innerHTML=`
    <div class="onboarding-title">How We Use Your Data</div>

    <div class="onboarding-data-info-section">
      <div class="onboarding-data-info-heading">Performance Data</div>
      <div class="onboarding-data-info-text">
        When enabled, ${D(e.name)} collects anonymous performance metrics such as memory usage,
        refresh timing, and WebSocket stability. This data helps us identify crashes,
        optimize resource usage, and ensure ${D(e.name)} runs smoothly on every device.
        No personal information, photos, or calendar data is ever included.
      </div>
    </div>

    <div class="onboarding-data-info-section">
      <div class="onboarding-data-info-heading">Wake Word Samples</div>
      <div class="onboarding-data-info-text">
        When enabled, short audio clips captured during wake word detection are shared
        with us to improve voice recognition accuracy. These clips are typically 1\u20132 seconds
        long and contain only the wake word trigger. They are used exclusively to train
        and refine the on-device wake word model so it works better for everyone.
      </div>
    </div>

    <div class="onboarding-data-info-section">
      <div class="onboarding-data-info-heading">Your Control</div>
      <div class="onboarding-data-info-text">
        Both of these can be turned off at any time in Settings. Performance data sharing
        is found under System &gt; Performance &amp; Diagnostics, and wake word sample
        sharing is under AI &amp; Voice &gt; Wake Word Collection.
      </div>
    </div>
  `;let o=document.createElement("button");return o.className="onboarding-btn-primary",o.textContent="Back",o.addEventListener("click",n),t.appendChild(o),t}function Ie({onGotIt:n}){let e=document.createElement("div");e.className="onboarding-tip-overlay";let t=!1;try{if(typeof DashieNative<"u"&&DashieNative.getDeviceInfo){let a=JSON.parse(DashieNative.getDeviceInfo());t=a.isTv||a.isFireTV||!1}}catch{}let o=t?"Press the Back button on your remote to open the sidebar":"Swiping away from the left edge of the screen opens the sidebar";return e.innerHTML=`
    <div class="onboarding-tip-card onboarding-tip-card--sidebar">
      <div class="onboarding-tip-arrow onboarding-tip-arrow--left"></div>
      <div class="onboarding-title" style="font-size: 20px; margin-bottom: 8px;">Quick Tip</div>
      <div class="onboarding-subtitle" style="margin-bottom: 20px;">${o}</div>
      <button class="onboarding-btn-primary" id="ob-tip-gotit">Got it</button>
    </div>
  `,e.querySelector("#ob-tip-gotit").addEventListener("click",n),e}function Te({onGoToControlCenter:n,onStart:e}){let t=H(),o=document.createElement("div");return o.className="onboarding-tip-overlay",o.innerHTML=`
    <div class="onboarding-tip-card onboarding-tip-card--menu">
      <div class="onboarding-tip-arrow onboarding-tip-arrow--left"></div>
      <div class="onboarding-title" style="font-size: 20px; margin-bottom: 8px;">Setting up ${D(t.name)}</div>
      <div class="onboarding-subtitle" style="margin-bottom: 20px;">You can configure everything in ${D(t.name)} from the Control Center, which you can reach from the sidebar</div>
      <div class="onboarding-btn-row">
        <button class="onboarding-btn-back" id="ob-tip-start">Start using ${D(t.name)}</button>
        <button class="onboarding-btn-primary" id="ob-tip-config">Go to Control Center</button>
      </div>
    </div>
  `,o.querySelector("#ob-tip-config").addEventListener("click",n),o.querySelector("#ob-tip-start").addEventListener("click",e),o}function D(n){return n.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}function xe(n){return/^https?:\/\//i.test(n)||(n="http://"+n),n=n.replace(/^(https?:\/\/\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\.(\d{4,5})(\/|$)/,"$1:$2$3"),n=n.replace(/\/+$/,""),n}function He(n,e,t){let o=n.querySelector(e);if(!o)return;let a=o.parentElement.querySelector(".onboarding-url-error");a&&a.remove();let i=document.createElement("div");i.className="onboarding-url-error",i.textContent=t,i.style.cssText="color: #FF6B6B; font-size: 12px; margin-top: 4px;",o.parentElement.appendChild(i),o.focus(),o.addEventListener("input",()=>i.remove(),{once:!0})}var ae,ve,st,rt,ie=O(()=>{z();ae=`<svg width="40" height="40" viewBox="0 0 240 240" fill="none" xmlns="http://www.w3.org/2000/svg">
  <path d="M229.39 109.153L130.61 10.3725C124.78 4.5425 115.23 4.5425 109.4 10.3725L10.61 109.153C4.78 114.983 0 126.512 0 134.762V224.762C0 233.012 6.75 239.762 15 239.762H107.27L66.64 199.132C64.55 199.852 62.32 200.262 60 200.262C48.7 200.262 39.5 191.062 39.5 179.762C39.5 168.462 48.7 159.262 60 159.262C71.3 159.262 80.5 168.462 80.5 179.762C80.5 182.092 80.09 184.322 79.37 186.412L111 218.042V102.162C104.2 98.8225 99.5 91.8425 99.5 83.7725C99.5 72.4725 108.7 63.2725 120 63.2725C131.3 63.2725 140.5 72.4725 140.5 83.7725C140.5 91.8425 135.8 98.8225 129 102.162V183.432L160.46 151.972C159.84 150.012 159.5 147.932 159.5 145.772C159.5 134.472 168.7 125.272 180 125.272C191.3 125.272 200.5 134.472 200.5 145.772C200.5 157.072 191.3 166.272 180 166.272C177.5 166.272 175.12 165.802 172.91 164.982L129 208.892V239.772H225C233.25 239.772 240 233.022 240 224.772V134.772C240 126.522 235.23 115.002 229.39 109.162V109.153Z" fill="#18bcf2"/>
</svg>`,ve=`<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" xmlns="http://www.w3.org/2000/svg">
  <rect x="2" y="2" width="9" height="9" rx="2"/>
  <rect x="13" y="2" width="9" height="4" rx="1.5"/>
  <rect x="13" y="8" width="9" height="3" rx="1.5"/>
  <rect x="2" y="13" width="4" height="4" rx="1"/>
  <rect x="7" y="13" width="4" height="4" rx="1"/>
  <rect x="2" y="18.5" width="4" height="3.5" rx="1"/>
  <rect x="7" y="18.5" width="4" height="3.5" rx="1"/>
  <rect x="13" y="13" width="9" height="9" rx="2"/>
</svg>`,st=`<svg width="40" height="40" viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg">
  <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/>
  <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/>
  <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/>
  <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/>
</svg>`,rt=`<svg width="64" height="64" viewBox="0 0 240 240" fill="none" xmlns="http://www.w3.org/2000/svg">
  <path d="M229.39 109.153L130.61 10.3725C124.78 4.5425 115.23 4.5425 109.4 10.3725L10.61 109.153C4.78 114.983 0 126.512 0 134.762V224.762C0 233.012 6.75 239.762 15 239.762H107.27L66.64 199.132C64.55 199.852 62.32 200.262 60 200.262C48.7 200.262 39.5 191.062 39.5 179.762C39.5 168.462 48.7 159.262 60 159.262C71.3 159.262 80.5 168.462 80.5 179.762C80.5 182.092 80.09 184.322 79.37 186.412L111 218.042V102.162C104.2 98.8225 99.5 91.8425 99.5 83.7725C99.5 72.4725 108.7 63.2725 120 63.2725C131.3 63.2725 140.5 72.4725 140.5 83.7725C140.5 91.8425 135.8 98.8225 129 102.162V183.432L160.46 151.972C159.84 150.012 159.5 147.932 159.5 145.772C159.5 134.472 168.7 125.272 180 125.272C191.3 125.272 200.5 134.472 200.5 145.772C200.5 157.072 191.3 166.272 180 166.272C177.5 166.272 175.12 165.802 172.91 164.982L129 208.892V239.772H225C233.25 239.772 240 233.022 240 224.772V134.772C240 126.522 235.23 115.002 229.39 109.162V109.153Z" fill="#18bcf2"/>
</svg>`});function N(){let n=typeof performance<"u"&&performance.now?performance.now():Date.now();return`[t=${Math.round(n-lt)}ms]`}var Le,g,lt,k,J,Ae=O(()=>{ie();z();Le=5e3,g="[Onboarding]",lt=typeof performance<"u"&&performance.now?performance.now():Date.now();k={DPAD_UP:19,DPAD_DOWN:20,DPAD_LEFT:21,DPAD_RIGHT:22,DPAD_CENTER:23,ENTER:66,BACK:4},J=class{constructor(){this.overlay=null,this.scanResults=null,this.scanning=!0,this.haLoginSuccess=!1,this.haConfigData=null,this.scanTimeoutId=null,this.permPollId=null,this._focusIndex=0,this._currentScreen=null,this.customUrlData=null}initialize(){console.log(g,N(),"initialize() called \u2014 starting onboarding flow"),this.overlay=document.createElement("div"),this.overlay.className="onboarding-overlay",this._isLightTheme()&&this.overlay.classList.add("light"),be(this.overlay),document.body.appendChild(this.overlay),window.dashieOnScanComplete=t=>this._onScanComplete(t),window.dashieOnHaLoginComplete=t=>this._onHaLoginComplete(t),window.dashieOnPermissionsComplete=()=>this._onPermissionsComplete(),window.dashieOnboardingDpad=t=>this._handleDpad(t);let e=typeof DashieNative<"u"?DashieNative:null;if(e&&e.hasPendingLoginSuccess&&e.hasPendingLoginSuccess()){console.log(g,"Pending login success found \u2014 skipping to post-login"),this.haLoginSuccess=!0,e.clearPendingLoginSuccess(),requestAnimationFrame(()=>{this.overlay.classList.add("visible")}),this._showDataCollection();return}if(e&&e.hasStoredHaCredentials&&e.hasStoredHaCredentials()){if(this.haLoginSuccess=!0,e.areOnboardingPermissionsGranted&&e.areOnboardingPermissionsGranted()){console.log(g,"Stored HA credentials + all permissions granted \u2014 completing onboarding"),this._complete();return}console.log(g,"Stored HA credentials found \u2014 resuming at post-login"),requestAnimationFrame(()=>{this.overlay.classList.add("visible")}),this._showDataCollection();return}if(e&&e.getPendingHaSetup&&e.getPendingHaSetup()){console.log(g,"Pending HA setup intent found \u2014 jumping to HA config"),e.clearPendingHaSetup(),this.scanning=!1;let t=this._loadSavedConfigFromPrefs();if(t&&t.url)try{let o=new URL(t.url);this.scanResults={ha:[{url:t.url,ip:o.hostname,port:o.port||(o.protocol==="https:"?"443":"80")}]}}catch{this.scanResults={ha:[{url:t.url,ip:t.url,port:""}]}}requestAnimationFrame(()=>{this.overlay.classList.add("visible")}),this._showHaConfig("",t);return}this._showLoading(),this._startScan(),requestAnimationFrame(()=>{this.overlay.classList.add("visible"),console.log(g,N(),"overlay visible class added (after rAF)")})}_isLightTheme(){try{let e=document.documentElement;if(e.classList.contains("theme-light"))return!0;if(e.classList.contains("theme-dark"))return!1;let t=localStorage.getItem("dashie-theme")||"";return!t||t==="light"||t.endsWith("-light")}catch{return!1}}destroy(){if(console.log(g,"Destroying onboarding overlay"),clearTimeout(this.scanTimeoutId),clearInterval(this.permPollId),delete window.dashieOnScanComplete,delete window.dashieOnHaLoginComplete,delete window.dashieOnPermissionsComplete,delete window.dashieOnboardingDpad,this.overlay){let e=this.overlay;e.classList.remove("visible"),setTimeout(()=>e.remove(),300),this.overlay=null}}_setContent(e,t){this.overlay&&(this.overlay.innerHTML="",this.overlay.appendChild(e),this._currentScreen=t||null,this._focusIndex=0,requestAnimationFrame(()=>this._updateFocusHighlight()))}_showLoading(){console.log(g,N(),"_showLoading() rendering loading card"),this._setContent(we(),"loading")}_showWelcome(){this._setContent(De({scanResults:this.scanResults,scanning:this.scanning,configuredHa:this._loadConfiguredHaForWelcome(),onHaPath:a=>this._showHaConfig(a),onStandalonePath:()=>this._handleStandalonePath(),onCustomUrlPath:()=>this._showCustomUrlConfig(),onCreateAccount:()=>this._handleDashieAccountPath("create"),onSignIn:()=>this._handleDashieAccountPath("signin"),onClose:()=>this._handleClose()}),"welcome");let e=this._getFocusableElements(),t=["ob-signin-btn","ob-ha-btn"],o=-1;for(let a of t)if(o=e.findIndex(i=>i.id===a),o>=0)break;o>=0&&(this._focusIndex=o,this._updateFocusHighlight())}_showHaConfig(e,t){t||(t=this._loadSavedConfigFromPrefs()),this._setContent(_e({prefilledUrl:e||"",savedConfig:t||null,onConnect:o=>this._handleConnect(o),onBack:()=>this._showWelcome(),onCustomUrl:()=>this._showCustomUrlConfig()}),"ha-config")}_showCustomUrlConfig(e=null){this._setContent(ke({savedConfig:e||this.customUrlData||null,onConnect:t=>this._applyCustomUrlConfig(t),onBack:()=>this._showHaConfig("",this.haConfigData)}),"custom-url-config")}_applyCustomUrlConfig(e){this.customUrlData=e;let t=typeof DashieNative<"u"?DashieNative:null;t&&(typeof t.setHomeAssistantSettings=="function"?t.setHomeAssistantSettings(JSON.stringify({core:{useCustomUrl:!0,customUrl:e.url,haEnabled:!1,isSetupComplete:!0}})):console.warn(g,"DROP: setHomeAssistantSettings missing \u2014 custom URL not persisted"),typeof t.setApiEnabled=="function"&&(t.setApiEnabled(e.apiEnabled),e.apiEnabled&&(typeof t.setApiPort=="function"&&t.setApiPort(e.apiPort),e.apiPassword&&typeof t.setApiPassword=="function"&&t.setApiPassword(e.apiPassword)))),this._showDataCollection()}_loadConfiguredHaForWelcome(){let e=typeof DashieNative<"u"?DashieNative:null;if(!e||!e.getHaConnectionSettings)return null;try{let t=JSON.parse(e.getHaConnectionSettings()),o=t&&t.url||"";return o?{url:o}:null}catch(t){return console.warn(g,"Failed to read configured HA for welcome card",t),null}}_loadSavedConfigFromPrefs(){let e=typeof DashieNative<"u"?DashieNative:null;if(!e||!e.getHaConnectionSettings)return null;try{let t=JSON.parse(e.getHaConnectionSettings());if(t&&(t.base_url||t.url))return{url:t.base_url||t.url||"",dashboardPath:t.dashboard||"",hideSidebar:t.hide_sidebar!==!1,hideTabs:!!t.hide_tabs,apiEnabled:!!t.api_enabled,apiPort:t.api_port||2323,apiPassword:t.api_password||"",autoBuild:t.auto_build!==!1}}catch(t){console.warn(g,"Failed to read saved HA config from prefs",t)}return null}_showDataCollection(){this._setContent(Ee({onContinue:({perfEnabled:t,wakeEnabled:o})=>{console.log(g,"Data collection preferences:",{perfEnabled:t,wakeEnabled:o}),this._saveDataCollectionPreferences(t,o);let a=!1;try{typeof DashieNative<"u"&&DashieNative.getDeviceType&&(a=DashieNative.getDeviceType()==="tv")}catch{}a?(console.log(g,"TV device \u2014 skipping permission prompt screen"),this._startTipFlow()):this._showPermissionPrompt()},onLearnMore:()=>this._showDataCollectionInfo()}),"data-collection");let e=this._getFocusableElements();e.length>0&&(this._focusIndex=e.length-1,this._updateFocusHighlight())}_showDataCollectionInfo(){this._setContent(Ne({onBack:()=>this._showDataCollection()}),"data-collection-info");let e=this._getFocusableElements();e.length>0&&(this._focusIndex=e.length-1,this._updateFocusHighlight())}_showPermissionPrompt(){this._setContent(Ce({onGrantNow:()=>{console.log(g,"User chose to grant permissions now");let t=typeof DashieNative<"u"?DashieNative:null;t&&t.requestAllOnboardingPermissions?t.requestAllOnboardingPermissions():this._startTipFlow()},onLater:()=>{console.log(g,"User chose to skip permissions");let t=typeof DashieNative<"u"?DashieNative:null;if(t&&t.setPermissionPromptDeclined)try{t.setPermissionPromptDeclined(!0)}catch{}this._startTipFlow()}}),"permissions");let e=this._getFocusableElements();e.length>0&&(this._focusIndex=e.length-1,this._updateFocusHighlight())}_startTipFlow(){console.log(g,"Starting tip flow \u2014 completing onboarding"),clearInterval(this.permPollId),this._complete()}_startScan(){console.log(g,N(),"_startScan() invoking DashieNative.startNetworkScan"),typeof DashieNative<"u"&&DashieNative.startNetworkScan?DashieNative.startNetworkScan():console.warn(g,N(),"DashieNative.startNetworkScan not available"),this.scanTimeoutId=setTimeout(()=>{this.scanning&&(console.log(g,N(),`Scan timeout fired (${Le}ms) \u2014 proceeding with empty results`),this.scanning=!1,this.scanResults=this.scanResults||{ha:[]},this._routeAfterScan())},Le)}_onScanComplete(e){console.log(g,N(),"Scan complete callback:",JSON.stringify(e)),clearTimeout(this.scanTimeoutId),this.scanning=!1,this.scanResults=e,this._routeAfterScan()}_routeAfterScan(){let e=this.scanResults?.ha?.[0]||null;console.log(g,N(),`_routeAfterScan: haFound=${!!e}`),requestAnimationFrame(()=>{console.log(g,N(),`Showing welcome card (haFound=${!!e})`),this._showWelcome()})}_handleConnect(e){console.log(g,"Connect:",e.url),this.haConfigData=e;let t=typeof DashieNative<"u"?DashieNative:null;if(t){let o=e.url.replace(/\/+$/,"");e.dashboardPath&&(o+="/"+e.dashboardPath.replace(/^\/+/,"")),this.pendingFullUrl=o,t.saveHaUrl(o),t.setHaHideSidebar(e.hideSidebar),t.setHaHideTabs(e.hideTabs),t.setApiEnabled(e.apiEnabled),e.apiEnabled&&(t.setApiPort(e.apiPort),e.apiPassword&&t.setApiPassword(e.apiPassword)),t.openHaLogin(e.url)}}_onHaLoginComplete(e){if(console.log(g,"HA login result:",e?.success),e?.success){this.haLoginSuccess=!0;let t=typeof DashieNative<"u"?DashieNative:null;t&&this.pendingFullUrl&&(console.log(g,"Saving HA URL:",this.pendingFullUrl),t.saveHaUrl(this.pendingFullUrl)),this._showDataCollection()}else console.log(g,"Login failed/cancelled \u2014 returning to HA config"),this._showHaConfig("",this.haConfigData)}_onPermissionsComplete(){console.log(g,"All permissions complete \u2014 starting tip flow"),this._startTipFlow()}_handleClose(){console.log(g,"Close button tapped \u2014 showing exit confirmation");let e=typeof DashieNative<"u"?DashieNative:null;e&&e.showExitConfirmation?e.showExitConfirmation():e&&e.exitApp&&e.exitApp()}_handleStandalonePath(){this._handleDashieAccountPath("choose")}_handleDashieAccountPath(e){console.log(g,N(),"_handleDashieAccountPath: invoking DashieNative.signIn(mode="+e+")");let t=typeof DashieNative<"u"?DashieNative:null;t&&t.signIn?t.signIn(e):console.warn(g,N(),"DashieNative.signIn not available \u2014 cannot route to login")}_getFocusableElements(){return this.overlay?Array.from(this.overlay.querySelectorAll("button:not([disabled]), input:not([disabled]), a[href], .onboarding-toggle")).filter(e=>e.offsetParent!==null):[]}_updateFocusHighlight(){let e=this._getFocusableElements();e.forEach((o,a)=>{o.classList.toggle("onboarding-dpad-focus",a===this._focusIndex)});let t=e[this._focusIndex];t&&t.scrollIntoView({block:"nearest",behavior:"smooth"})}_findSpatialTarget(e){let t=this._getFocusableElements(),o=t[this._focusIndex];if(!o)return-1;let a=o.getBoundingClientRect(),i=a.left+a.width/2,l=a.top+a.height/2,d=-1,c=1/0;for(let h=0;h<t.length;h++){if(h===this._focusIndex)continue;let r=t[h].getBoundingClientRect(),s=r.left+r.width/2,p=r.top+r.height/2,u=s-i,v=p-l,m=!1;switch(e){case"up":m=v<-5;break;case"down":m=v>5;break;case"left":m=u<-5;break;case"right":m=u>5;break}if(!m)continue;let f;e==="up"||e==="down"?f=Math.abs(v)+Math.abs(u)*1.5:f=Math.abs(u)+Math.abs(v)*1.5,f<c&&(c=f,d=h)}return d}_handleDpad(e){let t=this._getFocusableElements();if(t.length)switch(this._focusIndex>=t.length&&(this._focusIndex=t.length-1),e){case k.DPAD_UP:case k.DPAD_DOWN:case k.DPAD_LEFT:case k.DPAD_RIGHT:{if(e===k.DPAD_LEFT||e===k.DPAD_RIGHT){let i=t[this._focusIndex],l=i?.closest(".onboarding-btn-row");if(l){let d=Array.from(l.querySelectorAll("button")),c=d.indexOf(i),h=e===k.DPAD_RIGHT?1:-1,r=c+h;if(r>=0&&r<d.length){let s=t.indexOf(d[r]);if(s>=0){this._focusIndex=s,this._updateFocusHighlight();break}}}}let o={[k.DPAD_UP]:"up",[k.DPAD_DOWN]:"down",[k.DPAD_LEFT]:"left",[k.DPAD_RIGHT]:"right"},a=this._findSpatialTarget(o[e]);if(a<0&&(e===k.DPAD_LEFT||e===k.DPAD_RIGHT)){let i=e===k.DPAD_RIGHT?1:-1,l=this._focusIndex+i;if(l>=0&&l<t.length){let d=t[this._focusIndex].getBoundingClientRect(),c=t[l].getBoundingClientRect(),h=d.top+d.height/2,r=c.top+c.height/2;Math.abs(r-h)<30&&(a=l)}}a>=0&&(this._focusIndex=a,this._updateFocusHighlight());break}case k.DPAD_CENTER:case k.ENTER:{let o=t[this._focusIndex];if(!o)break;let a=o.tagName==="INPUT"&&o.type==="checkbox";o.classList.contains("onboarding-toggle")||a?(o.checked=!o.checked,o.dispatchEvent(new Event("change",{bubbles:!0}))):o.tagName==="INPUT"||o.tagName==="TEXTAREA"?(o.readOnly=!1,o.focus()):o.click();break}case k.BACK:{let o=document.activeElement;if(o&&(o.tagName==="INPUT"||o.tagName==="TEXTAREA")&&this.overlay?.contains(o)){o.readOnly=!0,o.blur();return}this.handleBack();break}}}handleBack(){switch(console.log(g,"Back from screen:",this._currentScreen),this._currentScreen){case"ha-config":case"custom-url-config":this._showWelcome();break;case"data-collection":this.customUrlData?this._showCustomUrlConfig(this.customUrlData):this._showHaConfig("",this.haConfigData);break;case"data-collection-info":this._showDataCollection();break;case"permissions":this._showDataCollection();break;default:this._handleClose();break}}_saveDataCollectionPreferences(e,t){let o=typeof DashieNative<"u"?DashieNative:null;o&&(o.setDashboardTelemetryEnabled&&o.setDashboardTelemetryEnabled(e),o.setSampleCollectionEnabled&&o.setSampleCollectionEnabled(t));let a=window.settingsStore;a&&(a.set("performance.dashboardTelemetryEnabled",e),a.set("voice.sampleCollectionEnabled",t)),console.log(g,"Data collection preferences saved",{perfEnabled:e,wakeEnabled:t})}_complete(){console.log(g,"Onboarding complete");let e=typeof DashieNative<"u"?DashieNative:null;e&&e.onOnboardingComplete&&e.onOnboardingComplete(),this.destroy()}}});var ct,Pe,y,Oe,se,re,$e=O(()=>{ct="dashie-power-management-config",Pe="dashie-power-engine-state",y="[PowerEngine]",Oe={enabled:!1,entityId:"",preferNight:!1,minThreshold:20,maxThreshold:80,emergencyThreshold:10,nightStart:"22:00",nightEnd:"06:00"},se=class{constructor(){this._intervalId=null,this._state="IDLE",this._lastToggleTime=0,this._lastBatteryLevel=null,this._lastSwitchState=null,this._manualOverrideUntil=0,this._overrideTarget=null,this._restoreState(),window.addEventListener("message",e=>{e.data?.type==="power-engine-switch-state"&&(this._lastSwitchState=e.data.state)}),window.addEventListener("power-watchdog-toggle",e=>{let{entityId:t,turnOn:o}=e.detail||{};if(!t)return;let a=o?"turn_on":"turn_off";if(console.log(`${y} Watchdog toggle: switch.${a} \u2192 ${t}`),typeof window.evalInHaIframe!="function"){console.warn(`${y} [watchdog] evalInHaIframe not available`);return}window.evalInHaIframe(`
                (function() {
                    try {
                        var hass = document.querySelector('home-assistant')?.hass;
                        if (!hass) { console.warn('PowerEngine: hass not available'); return; }
                        hass.callService('switch', '${a}', { entity_id: '${t}' });
                        console.log('PowerEngine: switch.${a} sent to ${t} (watchdog)');
                    } catch (e) { console.error('PowerEngine: switch toggle failed', e); }
                })();
            `)})}start(){this._intervalId||(console.log(`${y} Started (poll every ${6e4/1e3}s, state: ${this._state})`),this._syncConfigToNative(),this._evaluate(),this._intervalId=setInterval(()=>this._evaluate(),6e4))}stop(){this._intervalId&&(clearInterval(this._intervalId),this._intervalId=null,console.log(`${y} Stopped`))}get isRunning(){return this._intervalId!==null}onManualToggle(e,t=null){let o=e?"CHARGING":"IDLE";this._overrideTarget=t,t!=null?(this._manualOverrideUntil=Number.MAX_SAFE_INTEGER,console.log(`${y} Manual toggle: switch ${e?"ON":"OFF"} \u2192 state=${o} (override until ${t}%)`)):(this._manualOverrideUntil=Date.now()+600*1e3,console.log(`${y} Manual toggle: switch ${e?"ON":"OFF"} \u2192 state=${o} (override 10min)`)),this._setState(o)}_evaluate(){let e=this._loadConfig();if(!e.enabled||!e.entityId){this._intervalId&&console.log(`${y} Disabled or no entity \u2014 skipping`);return}let t=this._getBattery();if(t.level==null){console.log(`${y} Battery level unavailable \u2014 skipping`);return}this._querySwitchState(e.entityId);let o=this._isNightWindow(e.nightStart,e.nightEnd),a=e.minThreshold;e.preferNight&&!o&&(a=e.emergencyThreshold);let i=e.maxThreshold,l=this._lastSwitchState||"unknown",d=this._lastBatteryLevel,c=d!=null?t.level-d:null,h=c!=null?` (${c>=0?"+":""}${c}%)`:"",r=`bat:${t.level}%${h} chg:${t.charging} sw:${l} st:${this._state} range:${a}-${i}%`;if(console.log(`${y} \u2500\u2500 POLL \u2500\u2500 ${r}`),this._lastBatteryLevel=t.level,Date.now()<this._manualOverrideUntil){let s=this._overrideTarget;s!=null?s>=100&&t.level>=100?(console.log(`${y} Override target reached: bat ${t.level}% >= ${s}% \u2014 clearing override`),this._manualOverrideUntil=0,this._overrideTarget=null):s<=5&&t.level<=s?(console.log(`${y} Override target reached: bat ${t.level}% <= ${s}% \u2014 turning switch ON`),this._toggleSwitch(e.entityId,!0,"override-target-reached")&&this._setState("CHARGING"),this._manualOverrideUntil=0,this._overrideTarget=null):(console.log(`${y} Override active (target: ${s}%) \u2014 skipping hysteresis`),this._state==="CHARGING"&&!t.charging&&(console.warn(`${y} DISCONNECT during override: re-sending turn_on`),this._toggleSwitch(e.entityId,!0,"override-disconnect-detect"))):console.log(`${y} Manual override active \u2014 skipping hysteresis`);return}this._state==="CHARGING"&&!t.charging&&(console.warn(`${y} DISCONNECT: st=CHARGING but not charging (sw:${l}) \u2192 turn_on`),this._toggleSwitch(e.entityId,!0,"disconnect-detect")),this._state==="IDLE"&&t.level<=a?(console.log(`${y} ON: bat ${t.level}% <= ${a}% \u2192 switch ON`),this._toggleSwitch(e.entityId,!0,"hysteresis-on")&&this._setState("CHARGING")):this._state==="IDLE"&&t.level>=i&&t.charging?(console.log(`${y} GUARD OFF: bat ${t.level}% >= ${i}% while IDLE+charging \u2192 switch OFF`),this._toggleSwitch(e.entityId,!1,"idle-above-max")):this._state==="CHARGING"&&t.level>=i&&(console.log(`${y} OFF: bat ${t.level}% >= ${i}% \u2192 switch OFF`),this._toggleSwitch(e.entityId,!1,"hysteresis-off")&&this._setState("IDLE"))}_toggleSwitch(e,t,o){let a=Date.now();if(a-this._lastToggleTime<3e5){let l=Math.round((3e5-(a-this._lastToggleTime))/1e3);return console.log(`${y} BLOCKED (${o}): rapid-cycle, retry in ${l}s`),!1}if(typeof window.evalInHaIframe!="function")return console.warn(`${y} FAILED (${o}): evalInHaIframe not available`),!1;let i=t?"turn_on":"turn_off";return console.log(`${y} SEND: switch.${i} \u2192 ${e} (${o})`),window.evalInHaIframe(`
            (function() {
                try {
                    var hass = document.querySelector('home-assistant')?.hass;
                    if (!hass) { console.warn('PowerEngine: \u274C hass not available in iframe'); return; }
                    hass.callService('switch', '${i}', { entity_id: '${e}' });
                    console.log('PowerEngine: \u2705 switch.${i} sent to ${e}');
                } catch (e) { console.error('PowerEngine: \u274C switch toggle failed', e); }
            })();
        `),this._lastToggleTime=a,!0}_querySwitchState(e){typeof window.evalInHaIframe=="function"&&window.evalInHaIframe(`
            (function() {
                try {
                    var hass = document.querySelector('home-assistant')?.hass;
                    if (!hass) return;
                    var state = hass.states['${e}'];
                    var val = state ? state.state : 'entity_not_found';
                    window.parent.postMessage({ type: 'power-engine-switch-state', state: val }, '*');
                } catch (e) { /* ignore */ }
            })();
        `)}_getBattery(){try{if(window.dashieDevice?.getSystemMetrics){let e=JSON.parse(window.dashieDevice.getSystemMetrics());return{level:e.batteryPercent??null,charging:!!e.isCharging}}}catch(e){console.warn(`${y} Failed to read battery`,e)}return{level:null,charging:!1}}_isNightWindow(e,t){let o=new Date,a=o.getHours()*60+o.getMinutes(),[i,l]=e.split(":").map(Number),[d,c]=t.split(":").map(Number),h=i*60+l,r=d*60+c;return h<=r?a>=h&&a<r:a>=h||a<r}_setState(e){let t=this._state;this._state=e,this._consecutiveDrops=0;try{localStorage.setItem(Pe,JSON.stringify({state:e,timestamp:Date.now()}))}catch{}console.log(`${y} State: ${t} \u2192 ${e}`)}_restoreState(){try{let e=localStorage.getItem(Pe);if(e){let t=JSON.parse(e);this._state=t.state||"IDLE",console.log(`${y} Restored state: ${this._state} (saved ${Math.round((Date.now()-t.timestamp)/6e4)}min ago)`)}}catch{this._state="IDLE"}}_loadConfig(){try{let e=localStorage.getItem(ct);if(e)return{...Oe,...JSON.parse(e)}}catch{}return{...Oe}}_syncConfigToNative(){try{let e=this._loadConfig();window.dashieDevice?.syncPowerConfig&&(window.dashieDevice.syncPowerConfig(JSON.stringify(e)),console.log(`${y} Config synced to native watchdog`))}catch{}}},re=new se});function le(n){let e=document.documentElement,t={};for(let o=0;o<e.style.length;o++){let a=e.style[o];a.startsWith("--")&&(t[a]=e.style.getPropertyValue(a))}n?(e.classList.remove("theme-light"),e.classList.add("theme-dark")):(e.classList.remove("theme-dark"),e.classList.add("theme-light"));for(let[o,a]of Object.entries(t))e.style.setProperty(o,a);console.log("[ThemeUtils] Applied theme class:",n?"theme-dark":"theme-light")}function j(n){let e=n?"true":"false";typeof window.evalInHaIframe=="function"&&window.evalInHaIframe(`
      try {
        var ha = document.querySelector('home-assistant');
        if (ha) {
          ha.dispatchEvent(new CustomEvent('settheme', { detail: { dark: ${e} } }));
          console.log('[DashieLite] settheme synced: dark=${e}');
        }
      } catch(e) {}
    `)}var Re=O(()=>{});function dt(n){let e=document.createElement("div");e.id="ha-offline-overlay",e.style.cssText=`
    position: fixed; inset: 0; z-index: 5;
    display: flex; flex-direction: column;
    align-items: center; justify-content: center;
    background: #111; color: #ccc;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  `;let t=n;try{t=new URL(n).origin}catch{}return e.innerHTML=`
    <div style="text-align:center; max-width:400px; padding:24px;">
      <!-- HA logo with wifi-off badge (matches Kotlin-side indicator) -->
      <div style="position:relative; display:inline-block; margin-bottom:16px;">
        <svg width="64" height="64" viewBox="0 0 240 240" style="display:block;">
          <path d="M240,224.762C240,233.012 233.25,239.762 225,239.762H15C6.75,239.762 0,233.012 0,224.762V134.762C0,126.512 4.77,114.993 10.61,109.153L109.39,10.3725C115.22,4.5425 124.77,4.5425 130.6,10.3725L229.39,109.162C235.22,114.992 240,126.522 240,134.772V224.772V224.762Z" fill="#F2F4F9"/>
          <path d="M229.39,109.153L130.61,10.3725C124.78,4.5425 115.23,4.5425 109.4,10.3725L10.61,109.153C4.78,114.983 0,126.512 0,134.762V224.762C0,233.012 6.75,239.762 15,239.762H107.27L66.64,199.132C64.55,199.852 62.32,200.262 60,200.262C48.7,200.262 39.5,191.062 39.5,179.762C39.5,168.462 48.7,159.262 60,159.262C71.3,159.262 80.5,168.462 80.5,179.762C80.5,182.092 80.09,184.322 79.37,186.412L111,218.042V102.162C104.2,98.8225 99.5,91.8425 99.5,83.7725C99.5,72.4725 108.7,63.2725 120,63.2725C131.3,63.2725 140.5,72.4725 140.5,83.7725C140.5,91.8425 135.8,98.8225 129,102.162V183.432L160.46,151.972C159.84,150.012 159.5,147.932 159.5,145.772C159.5,134.472 168.7,125.272 180,125.272C191.3,125.272 200.5,134.472 200.5,145.772C200.5,157.072 191.3,166.272 180,166.272C177.5,166.272 175.12,165.802 172.91,164.982L129,208.892V239.772H225C233.25,239.772 240,233.022 240,224.772V134.772C240,126.522 235.23,115.002 229.39,109.162V109.153Z" fill="#18BCF2"/>
        </svg>
        <!-- Red wifi-off badge (top-right overlap) -->
        <div style="position:absolute; top:-4px; right:-4px; width:24px; height:24px; background:#DC3545; border-radius:50%; display:flex; align-items:center; justify-content:center;">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="white">
            <path d="M1,9l2,2c4.97-4.97 13.03-4.97 18,0l2-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3-3c-1.65-1.66-4.34-1.66-6,0zM5,13l2,2c2.76-2.76 7.24-2.76 10,0l2-2C15.14,9.14 8.87,9.14 5,13z"/>
            <line x1="3" y1="3" x2="21" y2="21" stroke="white" stroke-width="2.5" stroke-linecap="round"/>
          </svg>
        </div>
      </div>
      <h2 style="margin:0 0 8px; font-size:18px; font-weight:600; color:#eee;">
        Home Assistant Unavailable
      </h2>
      <p style="margin:0 0 16px; font-size:13px; color:#888; line-height:1.4;">
        Unable to connect to<br>
        <span style="color:#aaa; font-family:monospace; font-size:12px;">${t}</span>
      </p>
      <p id="ha-offline-status" style="margin:0; font-size:13px; color:#999;">
        Attempting to reconnect\u2026
      </p>
    </div>
  `,e}function ht(n){Fe();let e=0;X=setInterval(async()=>{e++,Z&&(Z.textContent=`Reconnecting\u2026 (attempt ${e})`);try{let t=new AbortController,o=setTimeout(()=>t.abort(),5e3);await fetch(n,{mode:"no-cors",cache:"no-store",signal:t.signal}),clearTimeout(o),console.log("[HaOffline] HA reachable \u2014 reconnecting");let a=ce;he(),a?.()}catch{}},1e4)}function Fe(){X&&(clearInterval(X),X=null)}function de(n,e){U||(console.log("[HaOffline] Showing offline overlay for:",n),ce=e,U=dt(n),document.body.appendChild(U),Z=document.getElementById("ha-offline-status"),ht(n))}function he(){Fe(),U&&(U.remove(),U=null,Z=null),ce=null}var U,Z,X,ce,Be=O(()=>{U=null,Z=null,X=null,ce=null});function Ke(){try{if(typeof DashieNative<"u"&&typeof DashieNative.getVideoFeedSettings=="function"){let n=DashieNative.getVideoFeedSettings();if(n){let e=JSON.parse(n);try{localStorage.setItem(Q,n)}catch{}return{...ue,...e}}}}catch(n){console.warn("[VideoFeedConfig] Native read failed, falling back to localStorage",n)}try{let n=localStorage.getItem(Q);if(n)return{...ue,...JSON.parse(n)}}catch(n){console.warn("[VideoFeedConfig] Could not read video feed config",n)}return{...ue}}var Q,ue,Me=O(()=>{Q="dashie-video-feeds-config",ue={enabled:!1,rules:[]}});function We(){window.dashieKioskSetSession=()=>{console.warn(Ue,"DROP: [unexpected] native pushed an account session into the Chickadee shell. This edition has no account, no session bridge and no cloud settings sync, so the session is discarded. Something upstream still thinks this build has accounts.")},console.info(Ue,"account/session bridge is absent by design (Chickadee edition)")}var Ue,qe=O(()=>{Ue="[KioskSyncStub]"});var mt=Xe(()=>{fe();z();Ae();ie();$e();Re();Be();Me();qe();var q=null;function pt(){let n=["music-player-play","music-player-pause","music-player-play-pause","music-player-next","music-player-previous","music-player-stop","music-player-toggle-minimize","music-player-voice-duck","music-player-voice-unduck","music-player-switch-entity","music-player-volume-set","music-player-play-media"],e=document.getElementById("ha-content");for(let t of n)window.addEventListener(t,o=>{e?.contentWindow?.postMessage({source:"dashie-parent",type:"dispatch-event",event:t,detail:o.detail||null},"*")});console.log("[KioskShell] Music event forwarding set up")}function gt(){let t=window.innerWidth/1368,o=window.innerHeight/768,a=Math.min(t,o);a<1.2&&(a=1.2),a>2.5&&(a=2.5),console.log(`[KioskShell] Viewport scale: ${a.toFixed(2)} (${window.innerWidth}\xD7${window.innerHeight} vs 1368\xD7768)`);let i=Math.min(window.innerWidth,window.innerHeight);document.documentElement.style.setProperty("--shell-scale",a);let l;i>=1e3?l=1.5:i>=750?l=1.3:l=1.15,document.documentElement.style.setProperty("--sidebar-boost",l);let d=l>=1.3?1:.7;document.documentElement.style.setProperty("--popout-spacing-factor",d);try{let c=localStorage.getItem("dashie-menu-size");c&&document.documentElement.style.setProperty("--menu-size",parseFloat(c)||1)}catch{}console.log(`[KioskShell] Sidebar boost: ${l}, popout spacing: ${d} (short dim: ${i}px)`)}function Ge(){console.log("[KioskShell] Initializing..."),document.body.classList.add("sidebar-hidden"),gt();try{let e=null,t=localStorage.getItem("dashie-dashboard-zoom");if(t)e=parseInt(t,10);else if(typeof DashieNative<"u"&&DashieNative.getDashboardZoom){let o=DashieNative.getDashboardZoom();if(o&&o!==100){e=o;try{localStorage.setItem("dashie-dashboard-zoom",String(o))}catch{}}}if(e){let o=Math.max(10,Math.min(300,e))/100;document.documentElement.style.setProperty("--dashboard-zoom",o),console.log(`[KioskShell] Dashboard zoom: ${o}`)}}catch{}document.documentElement.style.zoom="",document.documentElement.style.transform="",document.documentElement.style.transformOrigin="";try{typeof DashieNative<"u"&&DashieNative.isSystemDarkMode&&le(DashieNative.isSystemDarkMode())}catch{}window.dashieRefreshCameraVisibility=()=>{},pe(),We(),re.start(),window.__dashiePowerEngine=re,pt(),typeof DashieNative<"u"&&DashieNative.isSetupComplete&&!DashieNative.isSetupComplete()&&(console.log("[KioskShell] Setup not complete \u2014 launching onboarding"),q=new J,q.initialize()),typeof DashieNative<"u"&&DashieNative.onShellReady&&DashieNative.onShellReady(),console.log("[KioskShell] Ready")}var Ve="";window.addEventListener("message",n=>{if(n.data){if(n.data.type==="ha-url-changed"&&n.data.url){Ve=n.data.url;try{typeof DashieNative<"u"&&DashieNative.onHaUrlChanged&&DashieNative.onHaUrlChanged(n.data.url)}catch{}}if(n.data.type==="ingress-cookie"&&n.data.cookie&&n.data.origin)try{typeof DashieNative<"u"&&DashieNative.setIngressCookie&&(DashieNative.setIngressCookie(n.data.origin,n.data.cookie),console.log("[KioskShell] Relayed ingress cookie to CookieManager"))}catch{}if(n.data.source==="dashie-ha"&&n.data.type==="ha-tokens"&&n.data.tokens)try{typeof window.dashieDevice<"u"&&window.dashieDevice.syncHaTokens&&(window.dashieDevice.syncHaTokens(n.data.tokens),console.log("[KioskShell] Synced HA tokens to native"))}catch{}}});window.dashieGetHaUrl=function(){return Ve||""};function ze(n){let e=window._dashieInjectionScripts;if(!e||!n.contentDocument)return!1;try{let t=n.contentDocument,o=t.head||t.documentElement;if(!o)return!1;let a=t.createElement("script");if(a.textContent=`(function() {
      var origSend = WebSocket.prototype.send;
      WebSocket.prototype.send = function(data) {
        try {
          if (typeof data === 'string' && data.indexOf('set_user_data') !== -1) {
            var msg = JSON.parse(data);
            if (msg.type === 'frontend/set_user_data' && msg.key === 'theme') {
              console.log('[Dashie] Blocked HA theme persistence');
              return;
            }
          }
        } catch (e) {}
        return origSend.call(this, data);
      };
    })();`,o.appendChild(a),e.parentBridge){let i=t.createElement("script");i.textContent=e.parentBridge,o.appendChild(i)}if(e.wsProxy){let i=t.createElement("script");i.textContent=e.wsProxy,o.appendChild(i)}if(e.kioskCss){let i=t.createElement("script");i.textContent=e.kioskCss,o.appendChild(i)}return console.log("[KioskShell] Same-origin injection complete"),!0}catch(t){return console.warn("[KioskShell] Same-origin injection failed:",t.message),!1}}var L=null,Ye="",W=!1,A=null;window.dashieSetHaUrl=function(n){let e=document.getElementById("ha-content");if(!e)return;Ye=n,console.log("[KioskShell] Loading HA:",n),L&&clearTimeout(L);let t=window._dashieInjectionScripts!=null;t&&(e.style.visibility="hidden"),e.onload=function(){W=!1,A&&(clearTimeout(A),A=null);let o=!1;try{let a=e.contentDocument;if(!a)o=!0;else{let i=(a.body?.innerText||"").toLowerCase();(i.includes("webpage")||i.includes("err_")||i.includes("not available")||i.includes("can\u2019t reach")||i.includes("404")||i.includes("not found")||i.includes("502")||i.includes("bad gateway")||i.includes("503")||i.includes("service unavailable"))&&(o=!0),!o&&!a.querySelector("home-assistant")&&!a.querySelector("ha-authorize")&&(a.body?.innerHTML||"").length<5e3&&(o=!0)}}catch{o=!0}if(o){L&&(clearTimeout(L),L=null),console.log("[KioskShell] HA iframe loaded error page \u2014 showing offline overlay"),e.style.visibility="visible",typeof DashieNative<"u"&&DashieNative.onHaIframeError&&DashieNative.onHaIframeError(n),de(n,()=>{console.log("[KioskShell] HA reconnected \u2014 reloading iframe"),window.dashieReloadHaIframe()});return}L&&(clearTimeout(L),L=null),he(),console.log("[KioskShell] HA iframe loaded"),typeof DashieNative<"u"&&DashieNative.onHaIframeHealthy&&DashieNative.onHaIframeHealthy(),t&&(ze(e),setTimeout(()=>{e.style.visibility="visible"},150)),typeof DashieNative<"u"&&DashieNative.onHaIframeLoaded&&DashieNative.onHaIframeLoaded(),setTimeout(()=>{let a=document.documentElement.classList.contains("theme-dark");j(a),Je(),je(),ft()},2e3)},L=setTimeout(()=>{L=null,W=!1,A&&(clearTimeout(A),A=null),console.log("[KioskShell] HA iframe load timeout \u2014 showing offline overlay"),e.style.visibility="visible",typeof DashieNative<"u"&&DashieNative.onHaIframeError&&DashieNative.onHaIframeError(n),de(n,()=>{console.log("[KioskShell] HA reconnected \u2014 reloading iframe"),window.dashieReloadHaIframe()})},1e4),e.src=n};window.dashieReloadHaIframe=function(){if(W){console.log("[KioskShell] dashieReloadHaIframe ignored \u2014 reload already in flight");return}let n=document.getElementById("ha-content"),e=Ye;if(!n||!e){console.warn("[KioskShell] dashieReloadHaIframe: no iframe or URL yet");return}W=!0,A&&clearTimeout(A),A=setTimeout(()=>{A=null,W&&(W=!1,console.warn("[KioskShell] dashieReloadHaIframe failsafe \u2014 reload did not complete in 12s, releasing guard"),typeof DashieNative<"u"&&DashieNative.onHaIframeError&&DashieNative.onHaIframeError(e))},12e3);try{if(n.contentWindow&&n.contentWindow.location){console.log("[KioskShell] dashieReloadHaIframe \u2192 in-place reload (auth preserved)"),n.contentWindow.location.reload();return}}catch{console.log("[KioskShell] dashieReloadHaIframe: content window unreachable, about:blank fallback")}console.log("[KioskShell] dashieReloadHaIframe \u2192 about:blank fallback"),n.onload=null,n.src="about:blank",setTimeout(()=>{dashieSetHaUrl(e)},100)};window.dashieSetCustomUrl=function(n){let e=document.getElementById("custom-content"),t=document.getElementById("ha-content");e&&(console.log("[KioskShell] Loading custom URL:",n),t&&(t.style.display="none"),e.style.display="block",e.onload=function(){console.log("[KioskShell] Custom URL iframe loaded")},e.src=n)};window.dashieShowHaContent=function(){let n=document.getElementById("custom-content"),e=document.getElementById("ha-content");n&&(n.style.display="none",n.src=""),e&&(e.style.display=""),console.log("[KioskShell] Switched back to HA content")};window.dashieLoadHaWithTokens=function(n,e){let t=document.getElementById("ha-content");t&&(console.log("[KioskShell] Loading HA with token injection:",n),t.onload=function(){console.log("[KioskShell] HA iframe loaded (auth page) \u2014 injecting tokens");let o=!1;try{t.contentWindow&&t.contentWindow.localStorage&&(t.contentWindow.localStorage.setItem("hassTokens",e),console.log("[KioskShell] Tokens injected via direct localStorage access"),o=!0)}catch(a){console.warn("[KioskShell] Direct localStorage failed:",a.message)}if(!o){let a=e.replace(/\\/g,"\\\\").replace(/'/g,"\\'");t.contentWindow.postMessage({source:"dashie-parent",type:"eval",script:"try { localStorage.setItem('hassTokens', '"+a+"'); console.log('Dashie: Tokens injected into HA localStorage'); } catch(e) { console.error('Dashie: Token injection failed:', e); }"},"*")}setTimeout(()=>{console.log("[KioskShell] Reloading HA iframe after token injection"),t.onload=function(){console.log("[KioskShell] HA iframe loaded with tokens"),window._dashieInjectionScripts&&ze(t),typeof DashieNative<"u"&&DashieNative.onHaIframeLoaded&&DashieNative.onHaIframeLoaded(),setTimeout(()=>{let a=document.documentElement.classList.contains("theme-dark");j(a),Je()},2e3)},t.src=n},500)},t.src=n)};window.evalInHaIframe=function(n){let e=document.getElementById("ha-content");if(!e)return"no-iframe";try{if(e.contentDocument){let t=e.contentDocument.createElement("script");return t.textContent=n,(e.contentDocument.head||e.contentDocument.documentElement).appendChild(t),"same-origin"}}catch{}return e.contentWindow&&e.contentWindow.postMessage({source:"dashie-parent",type:"eval",script:n},"*"),"cross-origin"};function Je(){typeof window.evalInHaIframe=="function"&&window.evalInHaIframe(`
    try {
      var ha = document.querySelector('home-assistant');
      if (ha && ha.hass && ha.hass.callWS) {
        ha.hass.callWS({type: 'assist_pipeline/pipeline/list'}).then(function(result) {
          var pref = result.pipelines.find(function(p) { return p.id === result.preferred_pipeline; });
          if (pref && pref.name) {
            window.parent.postMessage({source: 'dashie-ha', type: 'preferred-pipeline-name', name: pref.name}, '*');
          }
        });
      }
    } catch(e) {}
  `)}window.addEventListener("message",function(n){if(n.data?.source==="dashie-ha"&&n.data?.type==="preferred-pipeline-name")try{localStorage.setItem("dashie-preferred-pipeline-name",n.data.name),console.log("[KioskShell] Cached preferred pipeline name:",n.data.name)}catch{}});function je(){let n=document.getElementById("ha-content");try{let e=n?.contentWindow;if(e?.localStorage){let t=e.localStorage.getItem("hassTokens");if(!t){let o=e.document?.querySelector("home-assistant")?.hass?.auth?.data;o?.access_token&&o?.refresh_token&&(t=JSON.stringify(o),e.localStorage.setItem("hassTokens",t),console.log("[KioskShell] Persisted in-memory HA token to localStorage (checkbox-independent)"))}if(t&&window.dashieDevice?.syncHaTokens){window.dashieDevice.syncHaTokens(t),console.log("[KioskShell] HA tokens synced to native (same-origin)");return}}}catch{}typeof window.evalInHaIframe=="function"&&window.evalInHaIframe(`
    try {
      var tokens = localStorage.getItem('hassTokens');
      if (!tokens) {
        var d = document.querySelector('home-assistant')?.hass?.auth?.data;
        if (d && d.access_token && d.refresh_token) {
          tokens = JSON.stringify(d);
          localStorage.setItem('hassTokens', tokens);
        }
      }
      if (tokens) {
        window.parent.postMessage({source: 'dashie-ha', type: 'ha-tokens', tokens: tokens}, '*');
      }
    } catch(e) {}
  `)}window.addEventListener("message",function(n){if(n.data?.source==="dashie-ha"&&n.data?.type==="ha-tokens"&&n.data.tokens)try{window.dashieDevice?.syncHaTokens&&(window.dashieDevice.syncHaTokens(n.data.tokens),console.log("[KioskShell] HA tokens synced to native"))}catch{}});setInterval(je,1200*1e3);function ft(){if(typeof window.evalInHaIframe=="function"){var n="unknown";try{typeof DashieNative<"u"&&DashieNative.getDeviceId&&(n=DashieNative.getDeviceId())}catch{}window.evalInHaIframe(`
    try {
      var hass = document.querySelector('home-assistant')?.hass;
      if (hass && hass.callApi) {
        Promise.all([
          hass.callApi('GET', 'dashie/feeds'),
          hass.callApi('GET', 'dashie/feeds/subscriptions/${n}')
        ]).then(function(results) {
          window.parent.postMessage({source: 'dashie-ha', type: 'video-feeds-sync', data: JSON.stringify({
            feeds: (results[0] || {}).feeds || {},
            subscription: results[1] || {}
          })}, '*');
        }).catch(function() {});
      }
    } catch(e) {}
  `)}}window.addEventListener("message",function(n){if(n.data?.source==="dashie-ha"&&n.data?.type==="video-feeds-sync")try{var e=JSON.parse(n.data.data),t=e.feeds||{},o=(e.subscription||{}).feed_modes||{},a=Object.keys(t);if(a.length===0)return;var i=Ke();if(!i.enabled){console.log("[KioskShell] Feed sync skipped \u2014 video feeds disabled");return}for(var l=new Set(a),d=new Set,c=0;c<a.length;c++){var h=t[a[c]].camera_entity_id;h&&d.add(h)}var r=i.rules.length;i.rules=(i.rules||[]).filter(function(w){return!(l.has(w.id)||w.cameraEntityId&&d.has(w.cameraEntityId))});for(var s=r-i.rules.length,p=new Set(i.rules.map(function(w){return w.id})),u=0,v=0;v<a.length;v++){var m=a[v];if(!p.has(m)){var f=t[m],S=(f.triggers||[])[0],C=o[m]||f.default_mode||"subscribed";i.rules.push({id:m,name:f.label||m,cameraEntityId:f.camera_entity_id||"",cameraName:f.label||"",triggerEntityId:S?S.entity_id:"",triggerState:S&&S.state||"on",autoDismissSeconds:f.auto_dismiss_seconds!=null?f.auto_dismiss_seconds:30,continueWhileActive:f.continue_while_active!=null?f.continue_while_active:!0,streamSourceType:f.stream_source_type||"entity",streamSourceUrl:f.stream_source_url||"",playSoundOnTrigger:!!f.alert_sound,triggerSound:f.alert_sound||"notify_bell_tap",enabled:C!=="ignored"}),u++}}(u>0||s>0)&&(Object.keys(o).length>0&&(i.feedModes=o),localStorage.setItem(Q,JSON.stringify(i)),typeof DashieNative<"u"&&DashieNative.saveVideoFeedConfig&&DashieNative.saveVideoFeedConfig(JSON.stringify(i)),console.log("[KioskShell] Feed sync: added="+u+", deduped="+s+", total="+i.rules.length))}catch(w){console.warn("[KioskShell] Feed sync error:",w)}});window.dashieToggleSidebar=function(n){console.log("[KioskShell] Toggle sidebar:",n),document.body.classList.toggle("sidebar-hidden",!n)};window.dashieSetSidebarConfig=function(n){};window.dashieRevealSidebar=function(){try{typeof DashieNative<"u"&&DashieNative.revealNativeSidebar()}catch{}};window.dashieStopSidebarAutoHide=function(){try{typeof DashieNative<"u"&&DashieNative.stopSidebarAutoHide()}catch{}};window.dashieDismissSidebar=function(){};window.dashieSetLockState=function(n){};window.dashieRefreshMusicVisibility=function(){};window.dashieShowOnboardingTips=function(){console.log("[KioskShell] Showing onboarding tips"),q=null,document.querySelectorAll(".onboarding-tip-overlay").forEach(s=>{console.log("[KioskShell] Removing orphaned tip overlay from a previous invocation"),s.remove()});let n=null,e=0;window.overlayHasKeyboardFocus=!0;try{DashieNative.setOverlayKeyboardFocus(!0)}catch{}document.querySelectorAll(".dm-dpad-focus").forEach(s=>s.classList.remove("dm-dpad-focus"));function t(){return n?Array.from(n.querySelectorAll("button")):[]}function o(){t().forEach((p,u)=>{p.classList.toggle("onboarding-dpad-focus",u===e)})}function a(s){let p=t();if(p.length)switch(s){case 21:case 19:e>0&&(e--,o());break;case 22:case 20:e<p.length-1&&(e++,o());break;case 23:case 66:p[e]?.click();break;case 4:p[0]?.click();break}}function i(s){let p=s||n;p&&(p===n&&(n=null),p.classList.remove("visible"),setTimeout(()=>p.remove(),300)),window.dashieOnboardingDpad=()=>{}}function l(){if(delete window.dashieOnboardingDpad,delete window.dashieOnSidebarDismissed,!window._shellSettingsDpad&&!window.dashieDashBarDpad){window.overlayHasKeyboardFocus=!1;try{DashieNative.setOverlayKeyboardFocus(!1)}catch{}}}function d(){let s=document.querySelector(".onboarding-highlight");s&&s.classList.remove("onboarding-highlight");try{typeof DashieNative<"u"&&DashieNative.dismissNativeSidebar()}catch{}l()}function c(){e=0,window.dashieOnboardingDpad=a,setTimeout(()=>{let s=t();e=s.length>1?s.length-1:0,o()},100)}function h(){try{typeof DashieNative<"u"&&DashieNative.openHamburgerPopout()}catch{}setTimeout(()=>{let s={};try{typeof DashieNative<"u"&&(s=JSON.parse(DashieNative.getControlCenterItemBounds()))}catch{}let p;p=Te({onGoToControlCenter:()=>{i(p),d(),setTimeout(()=>{typeof DashieNative<"u"&&DashieNative.openControlCenter?DashieNative.openControlCenter():console.warn("[KioskShell] DROP: openControlCenter unavailable (no DashieNative)")},300)},onStart:()=>{i(p),d()}}),n=p,document.body.appendChild(p);let u=p.querySelector(".onboarding-tip-card");if(u&&s.popoutRight>0&&s.controlCenterY>0){let v;try{let I=window.DashieNative?.getDisplayDensity?.();v=typeof I=="number"&&I>0?I:window.devicePixelRatio||1}catch{v=window.devicePixelRatio||1}let m=u.offsetWidth,f=u.offsetHeight,S=16,C=s.popoutRight/v+64;console.log("[KioskShell] Tip card layout debug",{rawBounds:s,dpr:v,innerWidth:window.innerWidth,innerHeight:window.innerHeight,cardWidth:m,cardHeight:f,cardLeftRaw:C}),C+m>window.innerWidth-S&&(C=window.innerWidth-S-m),C<S&&(C=S);let w=s.controlCenterY/v-f/2;w+f>window.innerHeight-S&&(w=window.innerHeight-S-f),w<S&&(w=S),u.style.left=C+"px",u.style.top=w+"px",u.style.transform="none";let P=u.querySelector(".onboarding-tip-arrow--left");P&&(P.style.top=s.controlCenterY/v-w+"px",P.style.transform="translateY(-50%)")}requestAnimationFrame(()=>p.classList.add("visible")),c()},300)}window.dashieOnSidebarDismissed=function(){n&&(console.log("[KioskShell] Sidebar dismissed during tip \u2014 auto-dismissing tip"),i(),l())},window.dashieRevealSidebar&&window.dashieRevealSidebar(),window.dashieStopSidebarAutoHide&&window.dashieStopSidebarAutoHide();let r=Ie({onGotIt:()=>{i(r),h()}});n=r,document.body.appendChild(r),requestAnimationFrame(()=>r.classList.add("visible")),c()};window.dashieHandleBack=function(){if(console.log("[KioskShell] Back pressed"),q&&q.overlay){console.log("[KioskShell] Back \u2192 onboarding"),q.handleBack();return}if(window._ccOverlayDpad){console.log("[KioskShell] Back \u2192 CC overlay"),window._ccOverlayDpad(4);return}if(window._shellSettingsDpad){console.log("[KioskShell] Back \u2192 shell settings"),window._shellSettingsDpad(4);return}if(window._overlayWantsKeys){console.log("[KioskShell] Back \u2192 overlay iframe");let n=document.getElementById("dashie-overlay");if(n?.contentWindow){n.contentWindow.postMessage({source:"dashie-parent",type:"remote-input",keyCode:4},"*");return}}console.log("[KioskShell] Back \u2192 reveal native sidebar (fallback)");try{typeof DashieNative<"u"&&DashieNative.revealNativeSidebar&&DashieNative.revealNativeSidebar()}catch(n){console.warn("[KioskShell] revealNativeSidebar failed:",n)}};window.onColorSchemeChanged=function(n){console.log("[KioskShell] Color scheme changed, isDark:",n),le(n),j(n)};document.readyState==="loading"?document.addEventListener("DOMContentLoaded",Ge):Ge();try{document.title=H().name}catch{}});export default mt();
