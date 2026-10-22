import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { MoreHorizontal, Pencil, Plus, Trash2, X } from 'lucide-react'
import { createProject, deleteProject, getProjects, renameProject } from '../api/projectApi'
import { apiErrorMessage } from '../api/errorMessage'
import Toast from '../components/Toast'
import { fullDate, initials, timeAgo } from '../utils/format'

const ROLE_LABELS = { OWNER: 'Propietario', EDITOR: 'Editor', VIEWER: 'Lector' }

export default function ProjectList() {
  const [projects, setProjects] = useState([]), [loading, setLoading] = useState(true), [error, setError] = useState('')
  const [modal, setModal] = useState(), [target, setTarget] = useState(), [name, setName] = useState(''), [description, setDescription] = useState(''), [confirmName, setConfirmName] = useState(''), [notice, setNotice] = useState('')
  async function load() { try { setProjects((await getProjects()).data) } catch (e) { setError(apiErrorMessage(e, 'No se pudieron cargar los proyectos.')) } finally { setLoading(false) } }
  useEffect(() => { void Promise.resolve().then(load); const refresh = () => load(); window.addEventListener('agent:data-changed', refresh); return () => window.removeEventListener('agent:data-changed', refresh) }, [])
  async function submit(event) { event.preventDefault(); setError(''); try { if (modal === 'new') { await createProject({ name: name.trim(), description }); setNotice('Proyecto creado.') } else { await renameProject(target.id, name.trim()); setNotice('Proyecto renombrado.') } setModal(); await load() } catch (e) { setError(apiErrorMessage(e, 'No se pudo guardar el proyecto.')) } }
  async function remove() { try { await deleteProject(target.id); setModal(); setConfirmName(''); setNotice('Proyecto eliminado.'); await load() } catch (e) { setError(apiErrorMessage(e, 'No se pudo eliminar el proyecto.')) } }
  const openNew = () => { setName(''); setDescription(''); setModal('new') }
  return <main className="content-page">
    <header className="content-header">
      <div><h1>Proyectos</h1><p>{loading ? 'Cargando…' : projects.length === 1 ? '1 proyecto' : `${projects.length} proyectos`} en los que participas.</p></div>
      <button className="primary-button" onClick={openNew}><Plus size={17}/> Nuevo proyecto</button>
    </header>
    {error && <p className="error-banner" role="alert">{error}</p>}
    {loading && <div className="skeleton-list" aria-hidden="true"><span/><span/><span/></div>}
    {!loading && !projects.length && <div className="empty-state"><h2>Todavía no hay proyectos</h2><p>Un proyecto agrupa los diagramas, las versiones y las personas que modelan contigo.</p><button className="primary-button" onClick={openNew}><Plus size={16}/> Crear el primero</button></div>}
    {!!projects.length && <section className="repository-list" aria-label="Lista de proyectos">{projects.map((project) => <article className="repository-row" key={project.id}>
      <span className="repo-icon" aria-hidden="true">{initials(project.name)}</span>
      <div className="repo-main"><Link to={`/projects/${project.id}`}>{project.name}</Link><p>{project.description || 'Sin descripción'}</p></div>
      <span className={`role-badge role-${project.role?.toLowerCase()}`}>{ROLE_LABELS[project.role] || project.role}</span>
      <time className="repo-time" dateTime={project.updatedAt} title={fullDate(project.updatedAt)}>{timeAgo(project.updatedAt)}</time>
      {project.role === 'OWNER' ? <details className="action-menu"><summary aria-label={`Acciones de ${project.name}`}><MoreHorizontal size={18}/></summary><div><button onClick={() => { setTarget(project); setName(project.name); setModal('rename') }}><Pencil size={15}/> Renombrar</button><button className="danger-action" onClick={() => { setTarget(project); setModal('delete') }}><Trash2 size={15}/> Eliminar</button></div></details> : <span className="action-menu-placeholder"/>}
    </article>)}</section>}
    {(modal === 'new' || modal === 'rename') && <Modal title={modal === 'new' ? 'Nuevo proyecto' : 'Renombrar proyecto'} close={() => setModal()}><form className="form modal-form" onSubmit={submit}><label>Nombre<input value={name} maxLength={120} autoFocus onChange={(e) => setName(e.target.value)} required/></label>{modal === 'new' && <label>Descripción <span className="optional">opcional</span><textarea value={description} onChange={(e) => setDescription(e.target.value)}/></label>}<div className="modal-actions"><button type="button" className="secondary-button" onClick={() => setModal()}>Cancelar</button><button className="primary-button">{modal === 'new' ? 'Crear proyecto' : 'Guardar nombre'}</button></div></form></Modal>}
    {modal === 'delete' && <Modal title="Eliminar proyecto" close={() => setModal()}><p>Se eliminará <strong>{target.name}</strong> con todas sus versiones, diagramas y artefactos. No se puede deshacer.</p><label className="confirm-name">Escribe <strong>{target.name}</strong> para confirmar<input value={confirmName} onChange={(e) => setConfirmName(e.target.value)}/></label><div className="modal-actions"><button className="secondary-button" onClick={() => setModal()}>Cancelar</button><button className="danger-button" disabled={confirmName !== target.name} onClick={remove}>Eliminar proyecto</button></div></Modal>}
    <Toast message={notice} onClose={() => setNotice('')}/>
  </main>
}
function Modal({ title, close, children }) { return <div className="export-backdrop" onMouseDown={close}><section className="export-dialog compact-dialog" role="dialog" aria-modal="true" aria-label={title} onMouseDown={(e) => e.stopPropagation()}><div className="modal-heading"><h2>{title}</h2><button className="icon-button" onClick={close} aria-label="Cerrar"><X size={18}/></button></div>{children}</section></div> }
