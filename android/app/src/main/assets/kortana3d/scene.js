// Kortana's 3D viewer — a hand-built, bone-rigged figure modeled from her
// reference art (see kortana_body.js), driven by procedural skeletal motion.
// Real joints bending, not a transform trick on a flat image: every gesture
// below rotates actual bones in her skeleton.
//
// Why procedural instead of a loaded animation clip library: her body is
// authored geometry on a custom skeleton (there is no matching clip file for
// it), so her movement is generated here by animating the bones directly.
// This also means "spin" and "backflip" are now REAL full-body moves, not
// honest substitutes for missing clips.
//
// Plain relative-path imports on purpose, not the bare 'three' specifier via a
// <script type="importmap">: import maps only landed in Android System WebView
// around Chrome 105 (2022), so on an older/frozen WebView the bare specifier
// fails to resolve and NOTHING renders. Relative paths need nothing beyond
// `type="module"`, which every real Android device has supported for years.
import * as THREE from './three/three.module.js';
import { createKortanaMaterial } from './shader.js';
import { buildKortana } from './kortana_body.js';

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(30, window.innerWidth / window.innerHeight, 0.1, 100);
camera.position.set(0, 1.02, 4.5);
camera.lookAt(0, 0.92, 0);

const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(window.devicePixelRatio || 1);
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.setClearColor(0x000000, 0); // fully transparent — the overlay behind shows through
document.body.appendChild(renderer.domElement);

scene.add(new THREE.AmbientLight(0xffffff, 0.65));
const key = new THREE.DirectionalLight(0xdfe6ff, 0.8);
key.position.set(1, 2, 2);
scene.add(key);
const rim = new THREE.DirectionalLight(0xb49bff, 0.5);
rim.position.set(-1.5, 1, -2);
scene.add(rim);

// ---- her iridescent materials (opal skin, pearl gown, sheer cape/skirt, hair) ----
const mats = {
    skin:     createKortanaMaterial({ base: 0xb9c0ec, glow: 0xb59bff, glowStrength: 0.85 }),
    gown:     createKortanaMaterial({ base: 0xc3bce8, glow: 0xc4a6ff, glowStrength: 1.05, opacity: 0.96 }),
    gownSheer:createKortanaMaterial({ base: 0xb1a9e0, glow: 0xc7b0ff, glowStrength: 1.25, opacity: 0.62, side: THREE.DoubleSide }),
    hair:     createKortanaMaterial({ base: 0xccd1e8, glow: 0xa793d8, glowStrength: 0.7 }),
};
const allMats = [mats.skin, mats.gown, mats.gownSheer, mats.hair];

// ---- build her ----
const kortana = buildKortana(mats);
scene.add(kortana.group);
const bones = kortana.bones;

// Capture the rest pose so per-frame animation is REST + idle + gesture deltas.
const ANIMATED = ['hips', 'spine', 'chest', 'neck', 'head',
    'upperArmL', 'forearmL', 'handL', 'upperArmR', 'forearmR', 'handR',
    'thighL', 'shinL', 'thighR', 'shinR', 'skirt', 'hair'];
const REST = {};
ANIMATED.forEach((n) => {
    const r = bones[n].rotation;
    REST[n] = { x: r.x, y: r.y, z: r.z };
});
function poseBone(name, dx = 0, dy = 0, dz = 0) {
    const r = REST[name];
    bones[name].rotation.set(r.x + dx, r.y + dy, r.z + dz);
}

// ===================== animation =====================
const smooth = (t) => t * t * (3 - 2 * t);         // smoothstep 0..1
const env = (e, dur, edge = 0.35) =>               // ramp-in/hold/ramp-out envelope
    e < edge ? smooth(e / edge)
        : e > dur - edge ? smooth(Math.max(0, (dur - e) / edge))
        : 1;

const GESTURES = {
    wave:     2.6,
    dance:    4.2,
    jump:     0.95,
    bounce:   1.4,
    spin:     0.95,
    backflip: 1.05,
};

let time = 0;
let gesture = null; // { name, start }

/** Always-on idle: breathing, weight-shift, gentle hair/gown sway. */
function idle(t) {
    poseBone('chest', Math.sin(t * 1.6) * 0.02, 0, 0);
    poseBone('spine', 0, Math.sin(t * 0.6) * 0.02, Math.sin(t * 0.8) * 0.015);
    poseBone('hips', 0, 0, Math.sin(t * 0.8) * 0.02);
    poseBone('neck', Math.sin(t * 1.6) * 0.015, 0, 0);
    poseBone('head', Math.sin(t * 0.7) * 0.03, Math.sin(t * 0.5) * 0.05, 0);
    poseBone('upperArmL', 0, 0, Math.sin(t * 0.9) * 0.03);
    poseBone('upperArmR', 0, 0, Math.sin(t * 0.9 + 1) * -0.03);
    poseBone('forearmL', Math.sin(t * 0.8) * 0.02, 0, 0);
    poseBone('forearmR', Math.sin(t * 0.8 + 1) * 0.02, 0, 0);
    poseBone('hair', Math.sin(t * 1.1) * 0.04, Math.sin(t * 0.6) * 0.03, 0);
    poseBone('skirt', 0, 0, Math.sin(t * 0.7) * 0.02);
}

/** Applies the active gesture on top of idle; returns group transform. */
function runGesture(name, e) {
    const gx = { y: 0, rotX: 0, rotY: 0 };
    switch (name) {
        case 'wave': {
            const a = env(e, GESTURES.wave, 0.4);
            const wig = Math.sin(e * 9) * 0.45 * a;
            poseBone('upperArmR', 0, 0.2 * a, -2.05 * a); // lift arm up & out
            poseBone('forearmR', 0, 0, -0.5 * a + wig);   // wave the forearm
            poseBone('handR', 0, wig * 0.6, 0);
            poseBone('head', 0.04 * a, -0.12 * a, 0);     // tilt toward the wave
            break;
        }
        case 'dance': {
            const a = env(e, GESTURES.dance, 0.5);
            const s = Math.sin(e * 3.0);
            poseBone('hips', 0, Math.sin(e * 1.5) * 0.18 * a, s * 0.07 * a);
            poseBone('spine', 0, Math.sin(e * 3.0) * 0.14 * a, -s * 0.05 * a);
            poseBone('chest', 0, 0, -s * 0.05 * a);
            poseBone('upperArmL', 0, 0, (-0.9 + s * 0.4) * a);
            poseBone('upperArmR', 0, 0, (0.9 - s * 0.4) * a);
            poseBone('forearmL', (-0.6 - s * 0.3) * a, 0, 0);
            poseBone('forearmR', (-0.6 + s * 0.3) * a, 0, 0);
            poseBone('head', Math.sin(e * 3) * 0.06 * a, s * 0.12 * a, 0);
            gx.y = Math.abs(Math.sin(e * 3)) * 0.03 * a;
            break;
        }
        case 'jump': {
            const p = e / GESTURES.jump;
            gx.y = Math.sin(p * Math.PI) * 0.38;
            const bend = (1 - Math.sin(p * Math.PI)) * 0.6;  // crouch on ground, straight airborne
            poseBone('shinL', bend, 0, 0); poseBone('shinR', bend, 0, 0);
            poseBone('thighL', -bend * 0.5, 0, 0); poseBone('thighR', -bend * 0.5, 0, 0);
            poseBone('upperArmL', 0, 0, -0.5 * Math.sin(p * Math.PI));
            poseBone('upperArmR', 0, 0, 0.5 * Math.sin(p * Math.PI));
            break;
        }
        case 'bounce': {
            const a = env(e, GESTURES.bounce, 0.3);
            gx.y = Math.abs(Math.sin(e * 6)) * 0.06 * a;
            poseBone('head', Math.sin(e * 6) * 0.12 * a, 0, 0);
            poseBone('chest', Math.sin(e * 6) * 0.03 * a, 0, 0);
            break;
        }
        case 'spin': {
            gx.rotY = smooth(Math.min(1, e / GESTURES.spin)) * Math.PI * 2;
            break;
        }
        case 'backflip': {
            const p = Math.min(1, e / GESTURES.backflip);
            gx.y = Math.sin(p * Math.PI) * 0.55;
            gx.rotX = -smooth(p) * Math.PI * 2;              // a REAL backward rotation
            const tuck = Math.sin(p * Math.PI);
            poseBone('shinL', tuck * 1.2, 0, 0); poseBone('shinR', tuck * 1.2, 0, 0);
            poseBone('thighL', -tuck * 0.9, 0, 0); poseBone('thighR', -tuck * 0.9, 0, 0);
            break;
        }
    }
    return gx;
}

// ===================== public API (called by Android via evaluateJavascript) =====================

/** Plays a named gesture. Names: wave, dance, jump, bounce, spin, backflip. */
window.playGesture = function (name) {
    const key = String(name || '').toLowerCase();
    if (!GESTURES[key]) {
        console.warn('Unknown gesture:', name);
        return;
    }
    gesture = { name: key, start: time };
};

const IDLE_FIDGET_GESTURES = ['wave', 'dance', 'bounce', 'spin'];
/** Fires a random idle fidget — used by the Android autonomy timer. */
window.playRandomIdleGesture = function () {
    window.playGesture(IDLE_FIDGET_GESTURES[Math.floor(Math.random() * IDLE_FIDGET_GESTURES.length)]);
};

/** Updates her glow accent color across skin, gown and hair — wired to the color picker. */
window.setGlowColor = function (hex) {
    allMats.forEach((m) => m.uniforms.uGlowColor.value.set(hex));
};

/** Ages her by level: she starts youthful and matures as she levels up. */
let currentLevel = 1;
window.setKortanaAge = function (level) {
    currentLevel = Math.max(1, Number(level) || 1);
    kortana.setAge(Math.min(1, (currentLevel - 1) / 9)); // fully mature by ~level 10
};
window.setKortanaAge(currentLevel);

window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
});

const clock = new THREE.Clock();
function animate() {
    requestAnimationFrame(animate);
    const delta = clock.getDelta();
    time += delta;

    allMats.forEach((m) => { m.uniforms.uTime.value = time; });

    // Reset per-frame group transform, then pose: REST + idle + (gesture).
    kortana.group.position.set(0, 0, 0);
    kortana.group.rotation.set(0, 0, 0);
    idle(time);

    if (gesture) {
        const e = time - gesture.start;
        const gx = runGesture(gesture.name, e);
        kortana.group.position.y = gx.y;
        kortana.group.rotation.y = gx.rotY;
        kortana.group.rotation.x = gx.rotX;
        if (e > GESTURES[gesture.name]) gesture = null;
    }

    renderer.render(scene, camera);
}
animate();

window.kortana3dReady = true;
