import { Route, Routes } from 'react-router-dom'
import './App.css'

import ProtectedRoute from './components/ProtectedRoute'
import DiagramEditor from './pages/DiagramEditor'
import Login from './pages/Login'
import ProjectDetail from './pages/ProjectDetail'
import ProjectList from './pages/ProjectList'
import Register from './pages/Register'

function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />

      <Route element={<ProtectedRoute />}>
        <Route path="/" element={<ProjectList />} />
        <Route path="/projects/:projectId" element={<ProjectDetail />} />
        <Route path="/projects/:projectId/versions/:versionId" element={<ProjectDetail />} />
        <Route path="/projects/:projectId/diagrams/new" element={<DiagramEditor />} />
        <Route path="/projects/:projectId/diagrams/:diagramId" element={<DiagramEditor />} />
        <Route path="/projects/:projectId/versions/:versionId/diagrams/new" element={<DiagramEditor />} />
        <Route path="/projects/:projectId/versions/:versionId/diagrams/:diagramId" element={<DiagramEditor />} />
      </Route>
    </Routes>
  )
}

export default App
