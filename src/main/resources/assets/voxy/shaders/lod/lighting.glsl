#ifndef _VOXY_LIGHTING_DECL
#define _VOXY_LIGHTING_DECL

vec2 getLightmapUv(uint index) {
    //The texel-centre convention (MC 26.x / upstream Voxy) is selectable; the default matches the
    //vanilla terrain this port targets (MC 1.20.x / 1.21.1 sample the left edges and let the LINEAR
    //filter blend the half texel).
    if (lightmapTexelCenter > 0.5f) {
        vec2 base = vec2((index >> 4) & 0xFu, index & 0xFu) / 15.0;
        return clamp(base * (15.0f / 16.0f) + (0.5f / 16.0f),
                vec2(8.0f / 256.0f), vec2(248.0f / 256.0f));
    }
    vec2 uv = vec2(float(index & 0xF0u), float((index & 0x0Fu) << 4u));
    return clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
}

#ifdef LIGHTING_SAMPLER_BINDING

layout(binding = LIGHTING_SAMPLER_BINDING) uniform sampler2D lightSampler;

vec4 getLighting(uint index) {
    // Base level only - the lightmap's mip selection jitters at LOD range and flickers the blocks
    return textureLod(lightSampler, getLightmapUv(index), 0.0);
}
#endif

#endif
