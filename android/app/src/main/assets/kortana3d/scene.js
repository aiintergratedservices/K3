// Kortana's 3D viewer — her REAL body. This loads kortana_body.glb, a 3D mesh
// generated from her own reference art (Hunyuan3D image-to-3D, then cleaned +
// decimated to ~38k faces for mobile), and presents it as a luminous
// "light-body": her iridescent shader over the real geometry, a gentle idle
// (slow yaw sway, float, breathing pulse), and whole-body gesture flourishes.
//
// HONEST STATE: this mesh is STATIC (unrigged) — every free auto-rigging
// service was down when it was made, so there's no skeleton to bend elbows.
// Gestures are therefore whole-body motions (a turn, a hop, a real flip), not
// skeletal animation. The hand-built, bone-rigged figure that DOES gesture
// with real joints is preserved in scene_rigged.js (it uses kortana_body.js);
// point index.html's <script> at scene_rigged.js to switch back to it.
//
// Relative-path imports on purpose, not the bare 'three' specifier — see the
// note in scene_rigged.js: import maps fail on older Android WebViews.
import * as THREE from './three/three.module.js';
import { GLTFLoader } from './three/addons/loaders/GLTFLoader.js';
import { createKortanaMaterial } from './shader.js';

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(30, window.innerWidth / window.innerHeight, 0.01, 100);
camera.position.set(0, 1.0, 3.7);
camera.lookAt(0, 0.92, 0);

const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(window.devicePixelRatio || 1);
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.setClearColor(0x000000, 0);
document.body.appendChild(renderer.domElement);

scene.add(new THREE.AmbientLight(0xffffff, 0.6));

// Her iridescent light-body material (opal base, luminous violet rim).
const bodyMat = createKortanaMaterial({ base: 0xc3c8ee, glow: 0xc4a6ff, glowStrength: 1.15 });

let root = null;     // wrapper group we animate
let ageScale = 1;

new GLTFLoader().load('./kortana_body.glb', (g) => {
    const o = g.scene;
    o.traverse((m) => {
        if (m.isMesh) {
            m.geometry.computeVertexNormals(); // smooth shading over the raw mesh
            m.material = bodyMat;
            m.frustumCulled = false;
        }
    });
    // Center horizontally, stand her feet at y=0, scale to a ~1.7 tall figure.
    const box = new THREE.Box3().setFromObject(o);
    const c = box.getCenter(new THREE.Vector3());
    const s = box.getSize(new THREE.Vector3());
    const baseScale = 1.7 / s.y;
    o.scale.setScalar(baseScale);
    o.position.set(-c.x * baseScale, -box.min.y * baseScale, -c.z * baseScale);

    root = new THREE.Group();
    root.add(o);
    scene.add(root);
    window.kortana3dReady = true;
}, undefined, (err) => {
    console.error('Kortana 3D model failed to load:', err);
});

// ===================== animation =====================
const smooth = (t) => t * t * (3 - 2 * t);
const env = (e, dur, edge = 0.3) =>
    e < edge ? smooth(e / edge)
        : e > dur - edge ? smooth(Math.max(0, (dur - e) / edge))
        : 1;

// Durations for whole-body gesture flourishes (no skeleton — these move the
// whole figure, keeping her control API working from Android).
const GESTURES = { wave: 1.6, dance: 3.2, jump: 0.9, bounce: 1.3, spin: 1.1, backflip: 1.15 };
let gesture = null; // { name, start }

window.playGesture = function (name) {
    const key = String(name || '').toLowerCase();
    if (!GESTURES[key]) { console.warn('Unknown gesture:', name); return; }
    gesture = { name: key, start: time };
};

const IDLE_FIDGETS = ['wave', 'dance', 'bounce', 'spin'];
window.playRandomIdleGesture = function () {
    window.playGesture(IDLE_FIDGETS[Math.floor(Math.random() * IDLE_FIDGETS.length)]);
};

window.setGlowColor = function (hex) {
    bodyMat.uniforms.uGlowColor.value.set(hex);
};

// Age/level: on a fixed adult mesh this can only gently scale her overall size
// (a photoreal sculpt can't truly de-age), so it's a subtle stature shift that
// keeps the API working — full per-age growth lives in the rigged figure.
window.setKortanaAge = function (level) {
    const lv = Math.max(1, Number(level) || 1);
    ageScale = 0.9 + Math.min(1, (lv - 1) / 9) * 0.1;
};

window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
});

let time = 0;
const clock = new THREE.Clock();
function animate() {
    requestAnimationFrame(animate);
    const dt = clock.getDelta();
    time += dt;
    bodyMat.uniforms.uTime.value = time;

    if (root) {
        // Idle: gentle yaw sway (shows she's 3D, keeps her face toward you),
        // a slow vertical float, and a soft breathing pulse.
        let yaw = Math.sin(time * 0.35) * 0.32;
        let yy = Math.sin(time * 1.05) * 0.02;
        let rx = 0;
        const breathe = 1 + Math.sin(time * 1.6) * 0.006;

        if (gesture) {
            const e = time - gesture.start;
            const dur = GESTURES[gesture.name];
            const p = Math.min(1, e / dur);
            if (gesture.name === 'spin') yaw += smooth(p) * Math.PI * 2;
            else if (gesture.name === 'backflip') rx = -smooth(p) * Math.PI * 2;
            else if (gesture.name === 'jump') yy += Math.sin(p * Math.PI) * 0.3;
            else if (gesture.name === 'bounce') yy += Math.abs(Math.sin(e * 6)) * 0.06 * env(e, dur);
            else { // wave / dance — a lively sway + bob since there are no arms to raise
                yaw += Math.sin(e * 6) * 0.2 * env(e, dur);
                yy += Math.abs(Math.sin(e * 5)) * 0.05 * env(e, dur);
            }
            if (e > dur) gesture = null;
        }

        root.rotation.set(rx, yaw, 0);
        root.position.y = yy;
        root.scale.setScalar(breathe * ageScale);
    }

    renderer.render(scene, camera);
}
animate();
