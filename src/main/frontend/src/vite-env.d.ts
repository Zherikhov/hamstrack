/// <reference types="vite/client" />

// swagger-ui-dist only types its main entry; we import the browser bundle
// directly because the main entry requires Node's `path` module
declare module 'swagger-ui-dist/swagger-ui-bundle' {
  import type { SwaggerUIBundle } from 'swagger-ui-dist'
  const bundle: SwaggerUIBundle
  export default bundle
}
