interface ManifestInput { file: File; partSize: number }

self.onmessage = async ({ data: { file, partSize } }: MessageEvent<ManifestInput>) => {
  try {
    const parts: { partNumber: number; sha256: string }[] = [];
    for (let start = 0; start < file.size; start += partSize) {
      const bytes = await file.slice(start, start + partSize).arrayBuffer();
      const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
      parts.push({ partNumber: parts.length + 1, sha256: btoa(String.fromCharCode(...digest)) });
    }
    self.postMessage({ parts });
  } catch {
    self.postMessage({ error: 'Không tính được mã kiểm tra của file.' });
  }
};

export {};
