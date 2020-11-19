export function getOriginKey(key) {
  return key.match(/\S*(?=\_)/g).shift()
}

export function isSumLastNode(key) {
  return /\_(?<=)\d*sumLastNode/g.test(key)
}

export function isSumNodeEnd(key) {
  return /\_(?<=)\S*sum/g.test(key)
}

export function removeRowColPrx(key) {
  return key.replace(/\_(?<=)\d*(rows|cols)\d*/g, '')
}

export function isQuotaSum(key, aggs) {
  return aggs.some((item) => {
    const reg = RegExp('(' + item + '()(?<=)(W*))', 'i')
    return reg.test(key)
  })
}
