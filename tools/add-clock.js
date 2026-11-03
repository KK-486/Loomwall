const fs = require('fs');
const path = process.argv[2];
const doc = JSON.parse(fs.readFileSync(path, 'utf8'));

const children = doc.root.children;
const hasClock = children.some((c) => c.id === 'text_clock');
if (!hasClock) {
  children.push(
    {
      id: 'text_clock',
      type: 'text',
      x: 10,
      y: 66,
      width: 62,
      height: 13,
      text: '{sys.time.hour:00}:{sys.time.minute:00}',
      fontSize: 9,
      align: 'left',
      color: '#FFFFFFFF',
      opacity: 1,
    },
    {
      id: 'text_battery',
      type: 'text',
      x: 10,
      y: 81,
      width: 62,
      height: 6,
      text: '电量 {sys.battery.level:0}%',
      fontSize: 4,
      align: 'left',
      color: '#FF8A94A6',
      opacity: 1,
    }
  );
  fs.writeFileSync(path, JSON.stringify(doc, null, 2) + '\n', 'utf8');
  console.log('已加入 text_clock / text_battery');
} else {
  console.log('文本节点已存在，跳过');
}
