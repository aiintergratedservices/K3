// Kortana's real 3D viewer — a rigged, skinned humanoid mesh (skeleton +
// bones), driven by real animation clips. This is what makes elbows/knees
// move for real: the mesh has joints, the clips animate those joints, this
// script just plays them back. Nothing here fakes movement by transforming
// a flat image.
//
// HONEST STATE (as of this build): the mesh is a placeholder ("RobotExpressive",
// an openly-licensed three.js sample rig) — not a sculpted likeness of Kortana.
// It was chosen because it ships a real animation library (Wave, Dance, Jump,
// etc.) that matches her existing gesture vocabulary. Her actual face/body has
// not been modeled in 3D yet — that needs an image-to-3D pipeline run on her
// turnaround reference sheet (identity/reference_art/kortana_3d_turnaround_sheet.png),
// which is a separate, not-yet-done step. The shader/glow IS real and applies
// to whatever mesh is loaded, so swapping in a real Kortana mesh later is a
// one-line path change, not a rewrite.

// Plain relative-path imports on purpose, not the bare 'three' specifier via
// the <script type="importmap"> that used to be in index.html: import maps
// only landed in Android System WebView around Chrome 105 (2022), so on an
// older/frozen WebView (common — not every device auto-updates it) the bare
// specifier fails to resolve, the whole module graph throws, and NOTHING
// renders — a real, hard-to-diagnose blank-screen bug with no visible error
// (this is what happened on first real-device test: the 3D viewport came up
// solid black). Relative paths need zero WebView feature beyond `type=
// "module"` itself, which every real Android device has supported for years.
import * as THREE from './three/three.module.js';
import { GLTFLoader } from './three/addons/loaders/GLTFLoader.js';
import { createKortanaMaterial } from './shader.js';

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(35, window.innerWidth / window.innerHeight, 0.1, 100);
camera.position.set(0, 1.1, 4.2);
camera.lookAt(0, 0.9, 0);

const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(window.devicePixelRatio || 1);
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.setClearColor(0x000000, 0); // fully transparent — the overlay behind shows through
document.body.appendChild(renderer.domElement);

// Soft fill light so the shader's Fresnel term has something to react to
// beyond pure emissive glow.
scene.add(new THREE.AmbientLight(0xffffff, 0.6));
const key = new THREE.DirectionalLight(0xffffff, 0.8);
key.position.set(1, 2, 2);
scene.add(key);

const material = createKortanaMaterial();

let mixer = null;
let model = null;
let actions = {}; // clip name -> THREE.AnimationAction
let currentAction = null;

// Real gesture vocabulary, mapped onto the placeholder rig's actual clip
// library. Some are exact matches; where there's no literal equivalent
// (spin, backflip) it's mapped honestly to the closest real clip, or to an
// actual full 3D turn of the model itself (real rotation, not a faked 2D
// spin) rather than inventing a clip that doesn't exist.
const GESTURE_CLIPS = {
    wave: 'Wave',
    dance: 'Dance',
    jump: 'Jump',
    bounce: 'Yes',       // closest real clip to a bounce — an actual nod motion
    backflip: 'Jump',    // no acrobatic clip exists on this rig; jump is the honest substitute
    spin: 'Idle',        // plays Idle while doing a real 360 turn of the whole model (see playGesture)
};
// Idle fidgets are picked directly from her real gesture vocabulary (keys of
// GESTURE_CLIPS) so every pick resolves to an actual clip. An earlier version
// listed raw clip names including 'ThumbsUp', which has no GESTURE_CLIPS key —
// it silently collapsed to a wave via the fallback below, so the fidget was
// never varied. 'spin'/'backflip' are left out on purpose: spin turns the
// whole model and backflip just re-plays Jump, both too much for a background
// idle twitch.
const IDLE_FIDGET_GESTURES = ['wave', 'dance', 'jump', 'bounce'];

const loader = new GLTFLoader();
loader.load('./placeholder_body.glb', (gltf) => {
    model = gltf.scene;
    model.scale.setScalar(1.0);

    model.traverse((child) => {
        if (child.isMesh) {
            child.material = material;
            child.frustumCulled = false;
        }
    });

    scene.add(model);

    mixer = new THREE.AnimationMixer(model);
    gltf.animations.forEach((clip) => {
        actions[clip.name] = mixer.clipAction(clip);
    });

    if (actions['Idle']) {
        currentAction = actions['Idle'];
        currentAction.play();
    }

    window.kortana3dReady = true;
}, undefined, (err) => {
    // Surfaced to Android via console.error -> WebChromeClient can log this;
    // no fallback rendering happens here on purpose — a blank overlay is an
    // honest failure state, not a silently-faked one.
    console.error('Kortana 3D model failed to load:', err);
});

/** Crossfades to a named gesture clip, then returns to Idle when it finishes (looped clips excepted). */
window.playGesture = function (name) {
    if (!mixer || !model) return;
    const clipName = GESTURE_CLIPS[name.toLowerCase()];
    const action = clipName ? actions[clipName] : null;
    if (!action) {
        console.warn('Unknown gesture or clip not present on this rig:', name);
        return;
    }

    if (name.toLowerCase() === 'spin') {
        realThreeSixtySpin();
    }

    const isLooping = clipName === 'Dance'; // let dance run a couple loops, everything else plays once
    action.reset();
    action.setLoop(isLooping ? THREE.LoopRepeat : THREE.LoopOnce, isLooping ? 2 : 1);
    action.clampWhenFinished = true;
    action.fadeIn(0.25);
    action.play();

    if (currentAction && currentAction !== action) {
        currentAction.fadeOut(0.25);
    }
    currentAction = action;

    const durationMs = (action.getClip().duration / action.timeScale) * 1000 * (isLooping ? 2 : 1);
    setTimeout(() => {
        if (currentAction === action && actions['Idle']) {
            action.fadeOut(0.4);
            actions['Idle'].reset().fadeIn(0.4).play();
            currentAction = actions['Idle'];
        }
    }, durationMs + 50);
};

/** Fires a random idle fidget — used by the Android autonomy timer, same real-clip constraint. */
window.playRandomIdleGesture = function () {
    const pick = IDLE_FIDGET_GESTURES[Math.floor(Math.random() * IDLE_FIDGET_GESTURES.length)];
    window.playGesture(pick);
};

/** Updates her glow accent color — wired to the existing color picker. */
window.setGlowColor = function (hex) {
    material.uniforms.uGlowColor.value.set(hex);
};

function realThreeSixtySpin() {
    if (!model) return;
    const start = model.rotation.y;
    const target = start + Math.PI * 2;
    const durationMs = 900;
    const startTime = performance.now();
    function step(now) {
        const t = Math.min(1, (now - startTime) / durationMs);
        const eased = t * t * (3 - 2 * t); // smoothstep
        model.rotation.y = start + (target - start) * eased;
        if (t < 1) requestAnimationFrame(step);
        else model.rotation.y = target % (Math.PI * 2);
    }
    requestAnimationFrame(step);
}

window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
});

const clock = new THREE.Clock();
function animate() {
    requestAnimationFrame(animate);
    const delta = clock.getDelta();
    if (mixer) mixer.update(delta);
    material.uniforms.uTime.value += delta;
    renderer.render(scene, camera);
}
animate();
