# ir part

this part we may want to add some ir implementation, ir information, ir analysis here

# issue

## BasicBlock

- `getTerminator()`
  - 对这个api的期望应该是返回一个block下的第一个终结指令
  - 应用场景: 我们在做visit的时候可能会出现如下的情况: br labelA; ret;
    - 在这个情况下我们出于某种原因多加了一个ret指令，但实际上在br lableA时候就已经跳转了，永远不会执行ret
    - 此时调用getTerminator()获得第一个终结符(br labelA)，而非ret，用来在visitor/pass阶段帮助分析


- 待讨论: 可能需要一个api来删除某个指令之后的所有指令
