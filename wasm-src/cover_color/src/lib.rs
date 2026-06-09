//! Deterministic book-cover colour, compiled from Rust to wasm32-unknown-unknown
//! (pure compute, no imports) — called from the cljw bookshelf via (wasm/call …).
//! The backend hashes a title to an i64; this module maps it to a pleasant colour
//! using a golden-ratio hue with fixed saturation/lightness, doing the HSL→RGB
//! maths in Rust. Returns a packed 0xRRGGBB i32.

#[no_mangle]
pub extern "C" fn color_from_hash(hash: i64) -> i32 {
    // Golden-ratio hue spread gives well-separated, repeatable hues.
    let h = ((hash as u64 as f64) * 0.61803398875) % 1.0;
    let (s, l) = (0.55_f64, 0.55_f64);
    let (r, g, b) = hsl_to_rgb(h, s, l);
    ((r as i32) << 16) | ((g as i32) << 8) | (b as i32)
}

/// A darker variant of the same hue, for text/border accents.
#[no_mangle]
pub extern "C" fn accent_from_hash(hash: i64) -> i32 {
    let h = ((hash as u64 as f64) * 0.61803398875) % 1.0;
    let (r, g, b) = hsl_to_rgb(h, 0.5, 0.32);
    ((r as i32) << 16) | ((g as i32) << 8) | (b as i32)
}

fn hsl_to_rgb(h: f64, s: f64, l: f64) -> (u8, u8, u8) {
    let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
    let hp = h * 6.0;
    let x = c * (1.0 - ((hp % 2.0) - 1.0).abs());
    let (r1, g1, b1) = match hp as i32 {
        0 => (c, x, 0.0),
        1 => (x, c, 0.0),
        2 => (0.0, c, x),
        3 => (0.0, x, c),
        4 => (x, 0.0, c),
        _ => (c, 0.0, x),
    };
    let m = l - c / 2.0;
    (
        (((r1 + m) * 255.0).round()) as u8,
        (((g1 + m) * 255.0).round()) as u8,
        (((b1 + m) * 255.0).round()) as u8,
    )
}
