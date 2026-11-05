/**
 * ESLint 10 (flat config) — chỉ dùng để kiểm ranh giới kiến trúc (AD-20). Người giữ: Anh.
 *
 * - `features/<m>` không import `features/<khác>`; `shared/` không import `features/` hay `app/`;
 *   `app/` dùng được `shared/` và cổng `index.ts` của từng feature.
 * - Chỉ `shared/api/client.ts` được gọi `fetch`.
 *
 * Parser: `@babel/eslint-parser` + plugin cú pháp `typescript`/`jsx` của Babel. TypeScript 7 (bản
 * viết lại bằng Go) không còn JS compiler API mà `typescript-eslint` cần, và `typescript-eslint`
 * 8.x chỉ nhận `typescript <6.1`. Babel tự phân tích cú pháp TS, không phụ thuộc gói `typescript`.
 * Kiểm kiểu do `tsc -b` (TS 7) lo; luật chất lượng code do oxlint lo (`.oxlintrc.json`).
 */
import babelParser from '@babel/eslint-parser';
import { createConfig } from 'eslint-plugin-boundaries/config';

const SOURCE_FILES = ['src/**/*.{ts,tsx}'];
const TEST_FILES = ['src/**/*.test.{ts,tsx}', 'src/test/**'];

/** Babel chỉ phân tích cú pháp, không biên dịch, không đọc file cấu hình Babel. */
function babelParserOptions(jsx) {
  return {
    requireConfigFile: false,
    babelOptions: {
      babelrc: false,
      configFile: false,
      parserOpts: { plugins: jsx ? ['typescript', 'jsx'] : ['typescript'] },
    },
  };
}

export default [
  { ignores: ['dist/**', 'coverage/**', 'playwright-report/**', 'test-results/**', 'node_modules/**'] },

  {
    linterOptions: { reportUnusedDisableDirectives: 'error' },
  },

  {
    files: ['src/**/*.ts'],
    languageOptions: { parser: babelParser, parserOptions: babelParserOptions(false) },
  },
  {
    files: ['src/**/*.tsx'],
    languageOptions: { parser: babelParser, parserOptions: babelParserOptions(true) },
  },

  {
    files: SOURCE_FILES,
    settings: {
      'import/resolver': {
        node: { extensions: ['.ts', '.tsx', '.js', '.jsx', '.json'] },
      },
    },
  },

  createConfig({
    files: SOURCE_FILES,
    settings: {
      'boundaries/elements': [
        { type: 'app', pattern: 'src/app' },
        { type: 'feature', pattern: 'src/features/*', capture: ['feature'] },
        { type: 'shared', pattern: 'src/shared' },
        { type: 'test-support', pattern: 'src/test' },
      ],
      // Điểm vào `src/main.tsx` không thuộc thư mục element nào nên được phân loại theo file.
      'boundaries/files': [
        { pattern: 'src/main.tsx', category: 'entry' },
        { pattern: '**/*.test.{ts,tsx}', category: 'test' },
      ],
    },
    rules: {
      'boundaries/dependencies': [
        'error',
        {
          default: 'disallow',
          policies: [
            {
              from: { file: { categories: 'entry' } },
              allow: { to: [{ element: { type: 'app' } }, { element: { type: 'shared' } }] },
            },
            {
              from: { element: { type: 'app' } },
              allow: {
                to: [
                  { element: { type: 'app' } },
                  { element: { type: 'shared' } },
                  { element: { type: 'feature', fileInternalPath: 'index.ts' } },
                ],
              },
            },
            {
              from: { element: { type: 'feature' } },
              allow: { to: { element: { type: 'shared' } } },
            },
            {
              from: { element: { type: 'shared' } },
              allow: { to: { element: { type: 'shared' } } },
            },
            {
              from: { element: { type: 'test-support' } },
              allow: { to: [{ element: { type: 'app' } }, { element: { type: 'shared' } }] },
            },
            {
              from: { file: { categories: 'test' } },
              allow: { to: { element: { type: 'test-support' } } },
            },
            // Các policy dưới chỉ để báo lỗi dễ hiểu; mặc định đã là cấm.
            {
              from: { element: { type: 'feature' } },
              disallow: { to: { element: { type: 'feature' } } },
              message:
                'features/{{ from.element.captured.feature }} không được import features/{{ to.element.captured.feature }} (AD-20). ' +
                'Kiểu dùng chung đặt ở shared/types/<module>.ts, gọi API qua shared/api.',
            },
            {
              from: { element: { type: 'feature' } },
              disallow: { to: [{ element: { type: 'app' } }, { file: { categories: 'entry' } }] },
              message: 'features/ không được import src/app (AD-20). Feature chỉ export FeatureDefinition ở index.ts.',
            },
            {
              from: { element: { type: 'shared' } },
              disallow: { to: [{ element: { type: ['feature', 'app'] } }, { file: { categories: 'entry' } }] },
              message: 'shared/ không được import features/ hay src/app (AD-20).',
            },
            {
              from: { element: { type: 'app' } },
              disallow: { to: { element: { type: 'feature', fileInternalPath: '!index.ts' } } },
              message:
                'src/app chỉ import features/{{ to.element.captured.feature }} qua index.ts, không import file bên trong.',
            },
          ],
        },
      ],
      'boundaries/no-unknown-dependencies': 'error',
      'boundaries/no-unknown-files': 'error',
    },
  }),

  {
    files: SOURCE_FILES,
    ignores: ['src/shared/api/client.ts', ...TEST_FILES],
    rules: {
      'no-restricted-globals': [
        'error',
        { name: 'fetch', message: 'Gọi API qua shared/api/client (AD-20), không gọi fetch trực tiếp.' },
      ],
      'no-restricted-properties': [
        'error',
        { object: 'window', property: 'fetch', message: 'Gọi API qua shared/api/client (AD-20).' },
        { object: 'globalThis', property: 'fetch', message: 'Gọi API qua shared/api/client (AD-20).' },
      ],
    },
  },
];
