declare module '@jbangdev/jbang' {
  export function exec(script: string, ...args: string[]): { exitCode: number; stdout: string; stderr: string };
}
