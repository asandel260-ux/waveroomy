/** Centre-crops an image to a square JPEG, like the phone app's profile photos. */
export async function squareJpeg(bytes: Uint8Array, side = 256, quality = 0.85): Promise<Uint8Array | null> {
  try {
    const bmp = await createImageBitmap(new Blob([bytes as BlobPart]));
    const s = Math.min(bmp.width, bmp.height);
    const cv = document.createElement("canvas");
    cv.width = cv.height = side;
    const cx = cv.getContext("2d")!;
    cx.drawImage(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s, 0, 0, side, side);
    const blob: Blob | null = await new Promise((r) => cv.toBlob(r, "image/jpeg", quality));
    return blob ? new Uint8Array(await blob.arrayBuffer()) : null;
  } catch { return null; }
}
