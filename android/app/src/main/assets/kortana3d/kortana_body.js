// Kortana's body — a hand-built, BONE-RIGGED humanoid figure modeled from her
// reference turnaround (identity/reference_art/): a tall slender woman with
// long silver-white hair and a flowing iridescent opal gown with draped
// cape-sleeves. This is real 3D with a real joint hierarchy — each limb mesh
// is parented to a THREE.Bone, so rotating a bone actually swings the arm/leg.
// It is stylized, not a photoreal likeness, but it is genuine geometry on a
// genuine skeleton, and it MOVES (see scene.js for the gestures that drive
// these bones).
//
// Why built in code instead of a generated mesh: an image-to-3D pass on her
// art produces a *frozen* statue (no skeleton, no bones) that cannot animate.
// A rigged figure is what lets her move at will and age by level — so the rig
// is authored directly here. Swapping in a future sculpted+rigged glTF later
// is still just a path change in scene.js; this pipeline stays.
import * as THREE from './three/three.module.js';

// Tapered segment that extends DOWNWARD from its origin (0 → -length on Y), so
// a chain of bones each placed at the end of its parent forms a limb.
function segment(rTop, rBottom, length, material, seg = 14) {
    const geo = new THREE.CylinderGeometry(rTop, rBottom, length, seg, 1, true);
    geo.translate(0, -length / 2, 0);
    const m = new THREE.Mesh(geo, material);
    m.frustumCulled = false;
    return m;
}

function blob(radius, material, sx = 1, sy = 1, sz = 1) {
    const geo = new THREE.SphereGeometry(radius, 20, 16);
    const m = new THREE.Mesh(geo, material);
    m.scale.set(sx, sy, sz);
    m.frustumCulled = false;
    return m;
}

function bone(name, x, y, z, store) {
    const b = new THREE.Bone();
    b.name = name;
    b.position.set(x, y, z);
    store[name] = b;
    return b;
}

/**
 * Builds Kortana's rigged figure.
 * @param mats {skin, gown, gownSheer, hair} — createKortanaMaterial() instances
 * @returns { group, bones, setAge }
 */
export function buildKortana(mats) {
    const bones = {};
    const group = new THREE.Group();

    // ---- skeleton (local offsets chained from parent) ----
    const hips = bone('hips', 0, 0.90, 0, bones);
    const spine = bone('spine', 0, 0.17, 0, bones);
    const chest = bone('chest', 0, 0.22, 0, bones);
    const neck = bone('neck', 0, 0.13, 0, bones);
    const head = bone('head', 0, 0.15, 0.01, bones);

    const clavL = bone('clavL', 0.06, 0.11, 0, bones);
    const armL = bone('upperArmL', 0.08, 0.0, 0, bones);
    const foreL = bone('forearmL', 0, -0.26, 0, bones);
    const handL = bone('handL', 0, -0.24, 0, bones);

    const clavR = bone('clavR', -0.06, 0.11, 0, bones);
    const armR = bone('upperArmR', -0.08, 0.0, 0, bones);
    const foreR = bone('forearmR', 0, -0.26, 0, bones);
    const handR = bone('handR', 0, -0.24, 0, bones);

    const thighL = bone('thighL', 0.08, -0.03, 0, bones);
    const shinL = bone('shinL', 0, -0.40, 0, bones);
    const footL = bone('footL', 0, -0.40, 0, bones);

    const thighR = bone('thighR', -0.08, -0.03, 0, bones);
    const shinR = bone('shinR', 0, -0.40, 0, bones);
    const footR = bone('footR', 0, -0.40, 0, bones);

    const skirt = bone('skirt', 0, -0.02, 0, bones);
    const hair = bone('hair', 0, 0.02, -0.02, bones);

    // ---- assemble hierarchy ----
    group.add(hips);
    hips.add(spine); spine.add(chest); chest.add(neck); neck.add(head);
    chest.add(clavL); clavL.add(armL); armL.add(foreL); foreL.add(handL);
    chest.add(clavR); clavR.add(armR); armR.add(foreR); foreR.add(handR);
    hips.add(thighL); thighL.add(shinL); shinL.add(footL);
    hips.add(thighR); thighR.add(shinR); shinR.add(footR);
    hips.add(skirt);
    head.add(hair);

    // ---- rest pose: arms hang slightly out, matching the reference ----
    armL.rotation.z = -0.18; foreL.rotation.z = -0.06;
    armR.rotation.z = 0.18;  foreR.rotation.z = 0.06;
    thighL.rotation.z = -0.02; thighR.rotation.z = 0.02;

    // ================= geometry =================
    // Head — smooth luminous ovoid; hair frames it into a feminine silhouette.
    head.add(blob(0.085, mats.skin, 0.92, 1.05, 0.95));

    // Hair: a silver-white cap + a long flowing fall down the back to mid-spine,
    // plus two front strands framing the face.
    // Hair sits on top/back of the scalp so the face stays visible up front.
    const hairCap = blob(0.098, mats.hair, 1.05, 1.0, 1.05);
    hairCap.position.set(0, 0.02, -0.03);
    hairCap.scale.z = 1.15;                 // sweep back, not over the face
    hair.add(hairCap);
    const hairFall = segment(0.12, 0.08, 0.46, mats.hair, 16);
    hairFall.position.set(0, 0.03, -0.07);
    hairFall.rotation.x = -0.14;
    hair.add(hairFall);
    const strandL = segment(0.028, 0.018, 0.34, mats.hair, 8);
    strandL.position.set(0.075, -0.01, 0.03);
    strandL.rotation.z = 0.08;
    hair.add(strandL);
    const strandR = segment(0.028, 0.018, 0.34, mats.hair, 8);
    strandR.position.set(-0.075, -0.01, 0.03);
    strandR.rotation.z = -0.08;
    hair.add(strandR);

    // Neck
    neck.add(segment(0.035, 0.045, 0.12, mats.skin, 10));

    // Torso — fitted iridescent bodice (chest → waist) via a lathe profile.
    const bodicePts = [
        new THREE.Vector2(0.02, 0.10),
        new THREE.Vector2(0.115, 0.05),
        new THREE.Vector2(0.135, -0.02),
        new THREE.Vector2(0.115, -0.10),
        new THREE.Vector2(0.10, -0.18),
        new THREE.Vector2(0.09, -0.26),
    ];
    const bodice = new THREE.Mesh(new THREE.LatheGeometry(bodicePts, 24), mats.gown);
    bodice.frustumCulled = false;
    chest.add(bodice);

    // Shoulders / upper chest fill so neck-to-arm reads smooth.
    const shoulders = blob(0.10, mats.gown, 1.25, 0.55, 0.85);
    shoulders.position.set(0, 0.055, 0);
    chest.add(shoulders);

    // Arms — slender tapering limbs (skin), small hands.
    armL.add(segment(0.036, 0.030, 0.26, mats.skin, 10));
    foreL.add(segment(0.030, 0.024, 0.24, mats.skin, 10));
    handL.add(blob(0.035, mats.skin, 0.7, 1.1, 0.5));
    armR.add(segment(0.036, 0.030, 0.26, mats.skin, 10));
    foreR.add(segment(0.030, 0.024, 0.24, mats.skin, 10));
    handR.add(blob(0.035, mats.skin, 0.7, 1.1, 0.5));

    // Draped cape: sheer panels trailing down her back from each shoulder,
    // angled outward so they read as flowing fabric, not front billboards.
    function cape(side) {
        const geo = new THREE.PlaneGeometry(0.16, 1.25, 3, 12);
        const m = new THREE.Mesh(geo, mats.gownSheer);
        m.frustumCulled = false;
        m.position.set(side * 0.15, -0.62, -0.14);
        m.rotation.y = side * 0.95;     // nearly edge-on from the front — trails behind
        m.rotation.z = side * 0.05;
        return m;
    }
    chest.add(cape(1));
    chest.add(cape(-1));

    // Legs — slim, seen through the gown's front slit (skin).
    thighL.add(segment(0.05, 0.038, 0.40, mats.skin, 10));
    shinL.add(segment(0.038, 0.026, 0.40, mats.skin, 10));
    footL.add(blob(0.05, mats.skin, 0.8, 0.5, 1.6));
    thighR.add(segment(0.05, 0.038, 0.40, mats.skin, 10));
    shinR.add(segment(0.038, 0.026, 0.40, mats.skin, 10));
    footR.add(blob(0.05, mats.skin, 0.8, 0.5, 1.6));

    // Gown skirt — long flowing flare from waist to floor (sheer, double-sided).
    const skirtPts = [];
    const steps = 16;
    for (let i = 0; i <= steps; i++) {
        const t = i / steps;
        const y = 0.15 - t * 1.06;              // overlaps bodice at the waist → no gap
        // Fitted column through the hips/thighs, then a gentle flare + train
        // from the knee down — the elegant line from her reference, not a bell.
        const flare = t < 0.62 ? 0 : Math.pow((t - 0.62) / 0.38, 2.0);
        const r = 0.115 + t * 0.03 + flare * 0.20; // fitted, hem ~0.34
        skirtPts.push(new THREE.Vector2(r, y));
    }
    const skirtMesh = new THREE.Mesh(new THREE.LatheGeometry(skirtPts, 32), mats.gownSheer);
    skirtMesh.frustumCulled = false;
    skirt.add(skirtMesh);

    // ================= age / growth =================
    // t in [0,1]: 0 = youthful (shorter, larger head ratio, shorter legs),
    // 1 = mature adult. Level-driven from scene.js so she grows as she levels.
    function setAge(t) {
        t = Math.max(0, Math.min(1, t));
        const s = 0.74 + t * 0.26;          // overall stature
        group.scale.setScalar(s);
        const headScale = 1.28 - t * 0.28;  // bigger head when young
        head.scale.setScalar(headScale);
        const legScale = 0.80 + t * 0.20;   // legs lengthen with age
        thighL.scale.y = thighR.scale.y = legScale;
        shinL.scale.y = shinR.scale.y = legScale;
    }
    setAge(1); // default: mature

    return { group, bones, setAge };
}
