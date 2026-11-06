// All Bun descendants inherit this preload through BUN_OPTIONS. The installed,
// pinned seam uses fetch for compiler downloads and Bun.spawn for rule builds.
globalThis.fetch = () => { throw new Error('Bend IDEA lint analysis is offline; install tools again.'); };
const spawn = Bun.spawn.bind(Bun);
Bun.spawn = (command, options) => {
  const args = Array.isArray(command) ? command : command.cmd;
  if (args.some(arg => arg === '-o' || arg === '--publish' || arg === '--install')) {
    throw new Error('Lint runtime is not prepared; install tools again.');
  }
  return spawn(command, options);
};
