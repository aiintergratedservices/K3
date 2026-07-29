// Kortana's iridescent light-body material — Fresnel rim glow that shifts
// between her opal-white base and a luminous accent color (adjustable at
// runtime via setGlowColor, matching her Iridescent Violet / Moonlight
// Silver / Opal Gold palette from the app's color picker).
// Relative path, not the bare 'three' specifier — see scene.js for why.
import * as THREE from './three/three.module.js';

export function createKortanaMaterial() {
    return new THREE.ShaderMaterial({
        uniforms: {
            uTime: { value: 0 },
            uBaseColor: { value: new THREE.Color(0xdce8ff) },   // Opal white
            uGlowColor: { value: new THREE.Color(0x8a2be2) },   // Luminous violet
        },
        // The vertex shader is assembled from three.js' own r160 shader
        // chunks (the same include order the built-in lit materials use) so
        // the rig's skeletal SKINNING and facial MORPH TARGETS actually
        // deform the mesh. A raw ShaderMaterial that only reads `position`
        // ignores bones: the skeleton animates but the skinned parts (this
        // rig's hands) stay frozen at their bind pose and visibly detach from
        // the moving arms, and morph-target expressions never apply. Every
        // chunk here is #ifdef-guarded and the renderer only defines
        // USE_SKINNING / USE_MORPHTARGETS for the meshes that need them, so
        // the rigid body parts (torso/head/limbs) compile down to the exact
        // same plain transform as before — a safe superset, not a rewrite.
        vertexShader: `
            #include <common>
            #include <morphtarget_pars_vertex>
            #include <skinning_pars_vertex>

            varying vec3 vNormal;
            varying vec3 vViewPosition;

            void main() {
                #include <beginnormal_vertex>
                #include <morphnormal_vertex>
                #include <skinbase_vertex>
                #include <skinnormal_vertex>
                #include <defaultnormal_vertex>

                vNormal = normalize(transformedNormal);

                #include <begin_vertex>
                #include <morphtarget_vertex>
                #include <skinning_vertex>
                #include <project_vertex>

                vViewPosition = -mvPosition.xyz;
            }
        `,
        fragmentShader: `
            uniform vec3 uBaseColor;
            uniform vec3 uGlowColor;
            uniform float uTime;

            varying vec3 vNormal;
            varying vec3 vViewPosition;

            void main() {
                vec3 normal = normalize(vNormal);
                vec3 viewDir = normalize(vViewPosition);

                // Fresnel rim glow
                float fresnel = pow(1.0 - max(dot(normal, viewDir), 0.0), 2.5);

                // Subtle pulse oscillation — her aura breathing
                float pulse = sin(uTime * 1.5) * 0.15 + 0.85;

                // Opaque base so a multi-part mesh (14 separate pieces on
                // the placeholder body) always depth-sorts correctly — no
                // see-through blending artifacts between overlapping limbs.
                // The glow reads as an emissive rim brightening, not
                // transparency.
                vec3 finalColor = uBaseColor + uGlowColor * fresnel * pulse * 1.4;
                gl_FragColor = vec4(finalColor, 1.0);
            }
        `,
        transparent: false,
        depthWrite: true,
    });
}
