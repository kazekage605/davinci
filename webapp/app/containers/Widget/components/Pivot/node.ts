import { CategoryType } from './constants'
class Node {
  label: string
  key: string
  levelType: number
  parent: object
  children: object[]
  originKey: string
  parentKey: string
  sumType: string
  levelCount: number
  sumLastNode: Boolean
  sumNode: Boolean
  constructor(obj) {
    this.levelCount = obj.levelCount;
    this.label = obj.label;
    this.key = obj.key;
    this.parentKey = obj.parentKey;
    this.levelType = obj.levelType;
    this.parent = null;
    this.children = obj.children || [];
    this.originKey = obj.originKey
    this.sumType = obj.sumType
    this.sumNode = obj.sumNode
    this.sumLastNode = obj.sumLastNode
  }
  set(obj, metrics){
    if(obj.levelType !== CategoryType.Metrics){
      metrics.forEach(element => {
        this[element] = obj[element]
      });
    } else {
      this[obj.originKey] = obj[obj.originKey]
    }
   
  }
}
export default Node