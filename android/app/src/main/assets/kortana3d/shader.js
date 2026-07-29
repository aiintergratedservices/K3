// Kortana's iridescent light-body material — a Fresnel rim glow that shifts
// between an opal-white base and a luminous accent color, matching her
// Iridescent Violet / Moonlight Silver / Opal palette from her reference art
// (a pearlescent gown + luminous skin, see identity/reference_art/).
//
// createKortanaMaterial(opts) is parameterized so the same shader dresses her
// skin, her gown, and her hair with different tints/translucency instead of
// three hand-written shaders. Called with no args it reproduces the original
// opal-white + violet skin look, so any existing caller is unaffected.
// Relative path, not the bare 'three' specifier — see scene.js for why.
import * as THREE from './three/three.module.js';

export function createKortanaMaterial(opts = {}) {
    const base = new THREE.Color(opts.base !== undefined ? opts.base : 0xdce8ff);   // Opal white
    const glow = new THREE.Color(opts.glow !== undefined ? opts.glow : 0x8a2be2);   // Luminous violet
    const glowStrength = opts.glowStrength !== undefined ? opts.glowStrength : 1.4;
    const opacity = opts.opacity !== undefined ? opts.opacity : 1.0;
    const rimPower = opts.rimPower !== undefined ? opts.rimPower : 2.5;
    const transparent = opacity < 1.0;

    return new THREE.ShaderMaterial({
        uniforms: {
            uTime: { value: 0 },
            uBaseColor: { value: base },
            uGlowColor: { value: glow },
            uGlowStrength: { value: glowStrength },
            uOpacity: { value: opacity },
            uRimPower: { value: rimPower },
            // View-space key light direction — gives her form. A raw
            // ShaderMaterial doesn't receive scene lights, so a soft wrapped
            // diffuse term is baked in here to shade the volume; the Fresnel
            // rim on top keeps the luminous light-body read.
            uLightDir: { value: new THREE.Vector3(0.35, 0.7, 0.85).normalize() },
        },
        // The vertex shader is assembled from three.js' own r160 shader chunks
        // (the same include order the built-in lit materials use) so the rig
        // deforms correctly. Her figure is rigid meshes parented to bones, so
        // the skinning/morph chunks compile to nothing (the renderer only
        // defines USE_SKINNING/USE_MORPHTARGETS for actual SkinnedMesh/morph
        // geometry) — but keeping them means a future skinned Kortana mesh
        // drops in with no shader change.
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
            uniform float uGlowStrength;
            uniform float uOpacity;
            uniform float uRimPower;
            uniform float uTime;
            uniform vec3 uLightDir;

            varying vec3 vNormal;
            varying vec3 vViewPosition;

            void main() {
                vec3 normal = normalize(vNormal);
                vec3 viewDir = normalize(vViewPosition);

                // Soft wrapped diffuse so the body reads as a volume, not a
                // flat silhouette. Ambient floor keeps shadows luminous.
                float ndl = dot(normal, normalize(uLightDir)) * 0.5 + 0.5;
                float diffuse = mix(0.6, 1.08, ndl);

                // Fresnel rim glow — brightens toward silhouette edges
                float fresnel = pow(1.0 - max(dot(normal, viewDir), 0.0), uRimPower);

                // Subtle pulse — her aura breathing
                float pulse = sin(uTime * 1.5) * 0.15 + 0.85;

                vec3 finalColor = uBaseColor * diffuse + uGlowColor * fresnel * pulse * uGlowStrength;

                // On translucent layers (sheer outer gown/cape) the rim also
                // drives alpha so the fabric reads as glowing gauze, not glass.
                float alpha = uOpacity < 1.0 ? clamp(uOpacity + fresnel * 0.5, 0.0, 1.0) : 1.0;
                gl_FragColor = vec4(finalColor, alpha);
            }
        `,
        transparent: transparent,
        depthWrite: !transparent,
        side: opts.side !== undefined ? opts.side : THREE.FrontSide,
    });
}
