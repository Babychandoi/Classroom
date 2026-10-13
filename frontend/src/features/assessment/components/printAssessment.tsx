import { renderToStaticMarkup } from 'react-dom/server';
import type { LearnerDocument,LearnerItem,AdvancedInteraction,IdText,ChoiceOption } from '../types';
import { ContentBlocks,RichLine } from './ContentBlocks';

export function printAssessment(document:LearnerDocument){
  const page=window.open('','_blank');if(!page)throw Error('Cho phép cửa sổ bật lên để in đề.');
  const item=(q:LearnerItem)=>{
    const x=q.interaction as AdvancedInteraction & {options?:ChoiceOption[];statements?:IdText[];left?:IdText[];right?:IdText[];items?:IdText[];bank?:string[];dropdowns?:{id:string;options:IdText[]}[]};
    return <article key={q.id}><h3>{q.label} <small>({q.points} điểm)</small></h3><p><RichLine text={q.prompt}/></p><ContentBlocks blocks={q.promptBlocks} images={document.images}/>
      {x.assetKey && document.images?.[x.assetKey] && <img src={document.images[x.assetKey].url} alt={document.images[x.assetKey].alt??'Hình câu hỏi'}/>}
      {x.options?.map((option,i)=><div key={option.id}><p>{String.fromCharCode(65+i)}. <RichLine text={option.text}/></p>{option.assetKey && document.images?.[option.assetKey] && <img src={document.images[option.assetKey].url} alt={document.images[option.assetKey].alt??'Hình phương án'}/>}</div>)}
      {x.statements?.map((statement,i)=><p key={statement.id}>{String.fromCharCode(97+i)}. <RichLine text={statement.text}/> □ Đúng □ Sai</p>)}
      {x.left && <table><thead><tr><th>Cột trái</th><th>Cột phải</th></tr></thead><tbody>{Array.from({length:Math.max(x.left.length,x.right?.length??0)},(_,i)=><tr key={i}><td>{x.left?.[i]?.text}</td><td>{x.right?.[i]?.text}</td></tr>)}</tbody></table>}
      {x.items?.map((step,i)=><p key={step.id}>□ {i+1}. {step.text}</p>)}
      {x.bank?.length ? <p>Ngân hàng từ: {x.bank.join(' · ')}</p>:null}
      {x.dropdowns?.map((blank,i)=><p key={blank.id}>Ô {i+1}: {blank.options.map(o=>o.text).join(' / ')}</p>)}
      {x.headers && <table><thead><tr><th>Hàng</th>{x.headers.map((h,i)=><th key={i}>{h}</th>)}</tr></thead><tbody>{x.rows?.map(row=><tr key={row.id}><th>{row.label}</th>{x.headers!.map((_,i)=>{const cell=x.cells?.find(c=>c.row===row.id && c.column===i);return <td key={i}>{cell?.editable?'................':cell?.text}</td>;})}</tr>)}</tbody></table>}
      {x.points && <><p>{x.yLabel??'y'} / {x.xLabel??'x'}</p><svg viewBox="0 0 360 200" width="360" height="200" aria-label="Lưới vẽ đồ thị">{Array.from({length:9},(_,i)=><g key={i}><line x1={20+i*40} x2={20+i*40} y1="10" y2="180" stroke="#ddd"/><line x1="20" x2="340" y1={20+i*20} y2={20+i*20} stroke="#ddd"/></g>)}<path d="M20 10V180H350" fill="none" stroke="#111"/></svg><p>Tọa độ x: {x.points.map(p=>p.x).join(', ')}</p></>}
      {x.steps?.map((step,i)=><div key={step.id}><p>Bước {i+1}: {step.prompt} ({step.points} điểm)</p><p>................................................................................</p></div>)}
      {!x.options && !x.statements && !x.headers && !x.steps && <div className="answer-lines">........................................................................................................<br/>........................................................................................................</div>}
    </article>;
  };
  const html=renderToStaticMarkup(<main><h1>{document.title}</h1><p>Họ và tên: .................................................... Lớp: ................</p>{document.audio && <p>Bài nghe: {document.audio.name}</p>}<p>{document.itemCount} câu · {document.totalPoints} điểm</p>{document.sections.map(section=><section key={section.id}><h2>{section.title}</h2><p>{section.instructions}</p>{section.audio && <p>Bài nghe: {section.audio.name}</p>}{section.nodes.map(node=>node.kind==='ITEM'?item(node):<div key={node.id}><aside><h3>{node.title} · {node.label}</h3><p>{node.instructions}</p><ContentBlocks blocks={node.stimulus} images={document.images}/>{node.audio && <p>Bài nghe: {node.audio.name}</p>}</aside>{node.children.map(item)}</div>)}</section>)}</main>);
  page.document.write(`<!doctype html><html lang="vi"><head><meta charset="utf-8"><title>Đề học viên</title><style>@page{size:A4;margin:18mm}body{font:12pt Arial,sans-serif;color:#111;line-height:1.55}h1{font-size:20pt}h2{font-size:15pt}h3{font-size:12pt}small{font-weight:normal}article{break-inside:avoid;margin:16px 0}aside{border:1px solid #999;padding:12px}img{max-width:100%;max-height:250px}table{border-collapse:collapse}td,th{border:1px solid;padding:6px}.answer-lines{line-height:2.5;overflow:hidden}audio,button{display:none}</style></head><body>${html}</body></html>`);
  page.document.close();void Promise.all(Array.from(page.document.images).map(img=>img.complete?Promise.resolve():new Promise<void>(resolve=>{img.onload=()=>resolve();img.onerror=()=>resolve();}))).then(()=>{page.focus();page.print();});
}
